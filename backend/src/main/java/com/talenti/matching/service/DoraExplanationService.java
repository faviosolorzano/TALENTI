package com.talenti.matching.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.talenti.matching.config.ExplanationAiProperties;
import com.talenti.matching.domain.CompatibilityCalculator;
import com.talenti.matching.domain.CompatibilityResult;
import com.talenti.matching.domain.CompatibilityResult.Level;
import com.talenti.matching.domain.CompatibilityResult.Outcome;
import com.talenti.matching.domain.ExplanationContent;
import com.talenti.matching.domain.ExplanationFingerprint;
import com.talenti.matching.domain.ExplanationRequest;
import com.talenti.matching.domain.ExplanationTemplate;
import com.talenti.matching.domain.ExplanationValidator;
import com.talenti.matching.domain.RequirementKind;
import com.talenti.matching.domain.SkillTerms;
import com.talenti.matching.persistence.ExplanationStore;
import com.talenti.matching.port.CompatibilityExplainerPort;
import com.talenti.matching.port.ExplainerException;
import com.talenti.matching.service.CompatibilityService.Evaluated;
import com.talenti.shared.ai.AiFunction;
import com.talenti.shared.ai.AiLevel;
import com.talenti.shared.ai.AiUsageEvent;
import com.talenti.shared.ai.AiUsageOutcome;
import com.talenti.shared.ai.AiUsageQuota;
import com.talenti.shared.ai.AiUsageRecorder;
import com.talenti.shared.ai.QuotaStatus;
import com.talenti.vacancy.SkillCatalog;
import com.talenti.vacancy.SkillInfo;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * "DORA te explica" (spec 015): solo a pedido, recalcula con los datos actuales, sirve del caché si la huella no cambió
 * y, si no, pide a la IA una redacción que SIEMPRE se valida; ante cualquier problema usa la plantilla (RF-25). El
 * porcentaje y el veredicto salen siempre del cálculo de 007 (015-11): la IA no los recibe ni los puede cambiar.
 *
 * <p>Nunca hay 5xx por la IA (RNF-12). Los registros solo dicen el motivo de la plantilla, nunca el texto (015-19).
 */
@Service
public class DoraExplanationService {

	private static final Logger log = LoggerFactory.getLogger(DoraExplanationService.class);

	/** Adaptador de la plantilla en {@code versions.adapter}. */
	public static final String TEMPLATE_ADAPTER = "template";

	/** Etiquetas que rodean lo enviado (solo para estimar el tamaño de la entrada). */
	private static final int ENVELOPE_CHARS = 20;

	public enum Source {

		AI, TEMPLATE

	}

	/** Por qué se usó la plantilla (015-10). */
	public enum FallbackReason {

		AI_DISABLED, TIMEOUT, AI_ERROR, PERSONAL_LIMIT, GLOBAL_LIMIT, INVALID_OUTPUT

	}

	public record Versions(String formula, String prompt, String template, String model, String adapter) {
	}

	/** Una brecha prioritaria con su habilidad (nombre vigente), tipo, resultado y acciones. */
	public record PracticeItem(String slug, String name, RequirementKind requirementKind, Outcome outcome,
			List<String> actions) {

		public PracticeItem {
			actions = List.copyOf(actions);
		}

	}

	/** La explicación que se responde (sección 6). Lleva texto para la persona: no se imprime. */
	public record DoraExplanation(UUID vacancyId, int percent, Level level, Source source, FallbackReason fallbackReason,
			boolean cached, ExplanationContent content, List<PracticeItem> practice, Versions versions,
			Instant generatedAt) {

		public DoraExplanation {
			practice = List.copyOf(practice);
		}

		@Override
		public String toString() {
			return "DoraExplanation[source=" + source + ", fallbackReason=" + fallbackReason + ", cached=" + cached + "]";
		}

	}

	private final CompatibilityService compatibility;

	private final SkillCatalog catalog;

	private final ExplanationStore store;

	private final ExplanationTemplate template;

	private final ExplanationValidator validator;

	private final CompatibilityExplainerPort explainer;

	private final ExplanationAiProperties ai;

	private final AiUsageQuota quota;

	private final AiUsageRecorder recorder;

	private final ExecutorService executor;

	private final TransactionTemplate transaction;

	private final JsonMapper json;

	private final Clock clock;

	DoraExplanationService(CompatibilityService compatibility, SkillCatalog catalog, ExplanationStore store,
			ExplanationTemplate template, ExplanationValidator validator,
			ObjectProvider<CompatibilityExplainerPort> explainer, ExplanationAiProperties ai, AiUsageQuota quota,
			AiUsageRecorder recorder, @Qualifier("explanationExecutor") ExecutorService executor,
			PlatformTransactionManager transactionManager, JsonMapper json, Clock clock) {
		this.compatibility = compatibility;
		this.catalog = catalog;
		this.store = store;
		this.template = template;
		this.validator = validator;
		this.explainer = explainer.getIfUnique();
		this.ai = ai;
		this.quota = quota;
		this.recorder = recorder;
		this.executor = executor;
		this.transaction = new TransactionTemplate(transactionManager);
		this.json = json;
		this.clock = clock;
	}

	/**
	 * 015-02: si se ofrece la explicación con IA (encendida y sin el tope mensual de gasto). No mira el cupo personal y
	 * no escribe nada.
	 */
	public boolean available() {
		return explainer != null && !quota.monthlySpendReached();
	}

	/** 015-01: 404 si la vacante no existe, está cerrada o el id no es válido; 409 si el resultado es insuficiente. */
	public DoraExplanation explain(UUID userId, String rawVacancyId) {
		Evaluated evaluated = compatibility.evaluateOpen(userId, rawVacancyId);
		CompatibilityResult result = evaluated.result();
		if (!result.calculated()) {
			throw new CompatibilityInsufficientException();
		}
		UUID vacancyId = evaluated.vacancy().id();
		ExplanationRequest request = ExplanationRequest.of(evaluated.vacancy().title(), result);
		return transaction.execute(status -> {
			store.lock(userId, vacancyId);
			return explainLocked(userId, vacancyId, result, request);
		});
	}

	// ----------------------------------------------------------------------------------------------- internos

	private DoraExplanation explainLocked(UUID userId, UUID vacancyId, CompatibilityResult result,
			ExplanationRequest request) {
		Optional<ExplanationStore.Stored> stored = store.find(userId, vacancyId);

		// 015-12 y 015-15: lo guardado con la misma huella se sirve siempre, sin IA y sin contar.
		String hash = explainer == null ? null
				: ExplanationFingerprint.of(request, CompatibilityCalculator.FORMULA_VERSION, explainer.promptVersion(),
						explainer.model());
		if (stored.isPresent() && current(stored.get(), request, hash)) {
			Optional<ExplanationContent> content = read(stored.get().content());
			if (content.isPresent() && covers(content.get(), request)) {
				ExplanationStore.Stored row = stored.get();
				return response(vacancyId, result, Source.AI, null, true, content.get(), request,
						new Versions(row.formulaVersion(), row.promptVersion(), template.version(), row.model(),
								row.adapter()),
						row.generatedAt());
			}
		}

		if (explainer == null) {
			return fallback(vacancyId, result, request, FallbackReason.AI_DISABLED);
		}
		if (quota.monthlySpendReached()) {
			return fallback(vacancyId, result, request, FallbackReason.GLOBAL_LIMIT);
		}
		QuotaStatus status = quota.check(userId, AiFunction.EXPLANATION);
		if (!status.allowed()) {
			return fallback(vacancyId, result, request, status.blockedBy() == QuotaStatus.Block.PERSONAL
					? FallbackReason.PERSONAL_LIMIT : FallbackReason.GLOBAL_LIMIT);
		}
		return callAi(userId, vacancyId, result, request, hash);
	}

	/**
	 * Con la IA encendida, la huella debe coincidir con la de ahora. Apagada, vale lo guardado si los datos y la fórmula
	 * no cambiaron (015-12: "si la IA está apagada y hay una explicación de IA vigente en caché, se devuelve esa").
	 */
	private static boolean current(ExplanationStore.Stored stored, ExplanationRequest request, String hash) {
		if (hash != null) {
			return hash.equals(stored.inputHash());
		}
		return CompatibilityCalculator.FORMULA_VERSION.equals(stored.formulaVersion())
				&& ExplanationFingerprint.of(request, stored.formulaVersion(), stored.promptVersion(), stored.model())
						.equals(stored.inputHash());
	}

	private DoraExplanation callAi(UUID userId, UUID vacancyId, CompatibilityResult result, ExplanationRequest request,
			String hash) {
		int inputChars = explainer.instructionChars() + request.json().length() + ENVELOPE_CHARS;
		UUID readId = UUID.randomUUID();
		CompatibilityExplainerPort.Answer answer;
		Future<CompatibilityExplainerPort.Answer> call = executor.submit(() -> explainer.explain(request));
		try {
			answer = call.get(ai.timeout().toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			call.cancel(true);
			record(readId, userId, AiUsageOutcome.FAILED_PLATFORM, inputChars, 0);
			return fallback(vacancyId, result, request, FallbackReason.TIMEOUT);
		}
		catch (ExecutionException e) {
			boolean malformed = e.getCause() instanceof ExplainerException failure
					&& failure.kind() == ExplainerException.Kind.MALFORMED;
			int outputChars = e.getCause() instanceof ExplainerException failure ? failure.outputChars() : 0;
			record(readId, userId, AiUsageOutcome.FAILED_PLATFORM, inputChars, outputChars);
			return fallback(vacancyId, result, request,
					malformed ? FallbackReason.INVALID_OUTPUT : FallbackReason.AI_ERROR);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			call.cancel(true);
			record(readId, userId, AiUsageOutcome.FAILED_PLATFORM, inputChars, 0);
			return fallback(vacancyId, result, request, FallbackReason.AI_ERROR);
		}
		catch (RuntimeException e) {
			call.cancel(true);
			record(readId, userId, AiUsageOutcome.FAILED_PLATFORM, inputChars, 0);
			return fallback(vacancyId, result, request, FallbackReason.AI_ERROR);
		}

		ExplanationValidator.Verdict verdict;
		try {
			verdict = answer == null ? null : validator.validate(answer.draft(), request, terms());
		}
		catch (RuntimeException e) {
			verdict = null;
		}
		int outputChars = answer == null ? 0 : answer.outputChars();
		if (verdict == null || !verdict.valid()) {
			if (verdict != null) {
				log.info("Explicación de la IA rechazada por la validación: {}", verdict.rejection());
			}
			record(readId, userId, AiUsageOutcome.FAILED_PLATFORM, inputChars, outputChars);
			return fallback(vacancyId, result, request, FallbackReason.INVALID_OUTPUT);
		}

		record(readId, userId, AiUsageOutcome.SUCCESS, inputChars, outputChars);
		Instant now = clock.instant();
		Versions versions = new Versions(CompatibilityCalculator.FORMULA_VERSION, explainer.promptVersion(),
				template.version(), explainer.model(), explainer.adapter());
		store.save(userId, vacancyId, new ExplanationStore.Stored(hash, versions.formula(), versions.prompt(),
				versions.template(), versions.model(), versions.adapter(), json.writeValueAsString(verdict.content()),
				now));
		return response(vacancyId, result, Source.AI, null, false, verdict.content(), request, versions, now);
	}

	private DoraExplanation fallback(UUID vacancyId, CompatibilityResult result, ExplanationRequest request,
			FallbackReason reason) {
		log.debug("Explicación de compatibilidad por plantilla: {}", reason);
		return response(vacancyId, result, Source.TEMPLATE, reason, false, template.write(request), request,
				new Versions(CompatibilityCalculator.FORMULA_VERSION, null, template.version(), null, TEMPLATE_ADAPTER),
				clock.instant());
	}

	/** 015-11: {@code percent} y {@code level} son siempre los del cálculo, nunca algo de la IA. */
	private static DoraExplanation response(UUID vacancyId, CompatibilityResult result, Source source,
			FallbackReason reason, boolean cached, ExplanationContent content, ExplanationRequest request,
			Versions versions, Instant generatedAt) {
		Map<String, ExplanationRequest.PriorityGap> gaps = new HashMap<>();
		for (ExplanationRequest.PriorityGap gap : request.priorityGaps()) {
			gaps.put(gap.slug(), gap);
		}
		List<PracticeItem> practice = new ArrayList<>();
		for (ExplanationContent.Practice item : content.practice()) {
			ExplanationRequest.PriorityGap gap = gaps.get(item.skillSlug());
			practice.add(new PracticeItem(gap.slug(), gap.skill(), gap.kind(), gap.outcome(), item.actions()));
		}
		return new DoraExplanation(vacancyId, result.percent(), result.level(), source, reason, cached, content,
				practice, versions, generatedAt);
	}

	/** Lo guardado sigue cubriendo exactamente las brechas prioritarias de ahora. */
	private static boolean covers(ExplanationContent content, ExplanationRequest request) {
		return content.practice().stream().map(ExplanationContent.Practice::skillSlug).toList()
				.equals(request.priorityGapSlugs());
	}

	private Optional<ExplanationContent> read(String content) {
		try {
			return Optional.ofNullable(json.readValue(content, ExplanationContent.class));
		}
		catch (JacksonException | IllegalArgumentException | NullPointerException e) {
			return Optional.empty();
		}
	}

	private List<SkillTerms> terms() {
		List<SkillTerms> terms = new ArrayList<>();
		for (SkillInfo skill : catalog.all()) {
			List<String> names = new ArrayList<>();
			names.add(skill.name());
			names.addAll(skill.aliases());
			terms.add(new SkillTerms(skill.slug(), names));
		}
		return terms;
	}

	/** 015-14: una fila por llamada, con tamaños y costo estimado; nunca contenido. */
	private void record(UUID readId, UUID userId, AiUsageOutcome outcome, int inputChars, int outputChars) {
		recorder.record(new AiUsageEvent(readId, userId, AiFunction.EXPLANATION, AiLevel.ENHANCED, explainer.adapter(),
				explainer.model(), outcome, inputChars, outputChars, cost(inputChars, outputChars)));
	}

	/** Tokens ≈ caracteres ÷ 4 (hacia arriba); el precio por millón de tokens da millonésimas de dólar por token. */
	long cost(int inputChars, int outputChars) {
		BigDecimal input = BigDecimal.valueOf(tokens(inputChars)).multiply(ai.prices().inputPerMillionTokens());
		BigDecimal output = BigDecimal.valueOf(tokens(outputChars)).multiply(ai.prices().outputPerMillionTokens());
		return input.add(output).setScale(0, RoundingMode.CEILING).longValueExact();
	}

	private static long tokens(int chars) {
		return (Math.max(0, chars) + 3L) / 4;
	}

}
