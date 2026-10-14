package com.talenti.cv.service;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import com.talenti.cv.AdapterInfo;
import com.talenti.cv.CvExtractionPort;
import com.talenti.cv.EnhancedCvExtractionPort;
import com.talenti.cv.RawSuggestion;
import com.talenti.cv.adapter.RulesCvExtractionAdapter;
import com.talenti.cv.config.AiCvProperties;
import com.talenti.cv.config.CvProperties;
import com.talenti.cv.domain.CvOutputValidator;
import com.talenti.cv.domain.SuggestionDraft;
import com.talenti.cv.domain.TextLimiter;
import com.talenti.cv.persistence.CvRepository;
import com.talenti.shared.ai.AiFunction;
import com.talenti.shared.ai.AiLevel;
import com.talenti.shared.ai.AiUsageEvent;
import com.talenti.shared.ai.AiUsageOutcome;
import com.talenti.shared.ai.AiUsageQuota;
import com.talenti.shared.ai.AiUsageRecorder;
import com.talenti.shared.ai.QuotaStatus;
import com.talenti.vacancy.SkillCatalog;

import jakarta.annotation.PreDestroy;

/**
 * El servicio de lecturas: el ÚNICO punto de entrada a los adaptadores de extracción (009-25). Ninguna otra clase
 * llama a {@link CvExtractionPort#extract}; una prueba de arquitectura lo vigila. Una lectura solo ocurre por una
 * acción explícita de la persona (subir un archivo o pedir otra lectura): este servicio nunca se invoca al abrir
 * pantallas, consultar el estado, iniciar sesión ni desde una tarea programada.
 *
 * <p>Decide el nivel de lectura (009-27): la básica (reglas, local, gratuita) es la de siempre; la mejorada (IA externa)
 * solo si se pidió y se cumplen la bandera, el cupo personal, el tope global y la huella. Hace UNA llamada a la IA por
 * lectura, sin reintentos automáticos, con el texto ya limitado (009-30), y anota cada intento sin contenido
 * (009-31). Si la IA no responde, falla o no hay cupo, degrada a la lectura básica sin perder el CV (009-29). No escribe
 * contenido del CV en los registros.
 */
@Service
public class CvReadService {

	private static final Logger log = LoggerFactory.getLogger(CvReadService.class);

	private final CvExtractionPort rules;

	private final ObjectProvider<EnhancedCvExtractionPort> enhanced;

	private final SkillCatalog catalog;

	private final CvOutputValidator validator;

	private final AiUsageQuota quota;

	private final AiUsageRecorder recorder;

	private final AiCvProperties ai;

	private final CvProperties properties;

	private final CvRepository repository;

	private final Clock clock;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	CvReadService(RulesCvExtractionAdapter rules, ObjectProvider<EnhancedCvExtractionPort> enhanced,
			SkillCatalog catalog, AiUsageQuota quota, AiUsageRecorder recorder, AiCvProperties ai,
			CvProperties properties, CvRepository repository, Clock clock) {
		this.rules = rules;
		this.enhanced = enhanced;
		this.catalog = catalog;
		this.validator = new CvOutputValidator(catalog::findBySlug, catalog::resolve, 10);
		this.quota = quota;
		this.recorder = recorder;
		this.ai = ai;
		this.properties = properties;
		this.repository = repository;
		this.clock = clock;
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

	// ------------------------------------------------------------------------------------------ decisión

	/** Si la lectura mejorada está encendida y tiene adaptador. */
	public boolean enhancedAvailable() {
		return ai.cv().enabled() && enhanced.getIfAvailable() != null;
	}

	/** Información del adaptador de la lectura básica (para guardarla con el análisis, RF-45). */
	public AdapterInfo basicInfo() {
		return rules.info();
	}

	/** Información del adaptador de la lectura mejorada, si lo hay. */
	public AdapterInfo enhancedInfo() {
		EnhancedCvExtractionPort port = enhanced.getIfAvailable();
		return port == null ? null : port.info();
	}

	/**
	 * Decide qué lectura se hará. No llama a ningún adaptador. Si la persona pidió la mejorada: sin bandera, degrada
	 * ({@code DISABLED}); con la misma huella ya leída con IA en un análisis cerrado, degrada ({@code ALREADY_READ});
	 * sin cupo personal, 429 {@code ai-quota-reached} (el intento se anota); sin cupo global, degrada
	 * ({@code GLOBAL_LIMIT}, el intento se anota).
	 */
	public ReadPlan plan(UUID userId, String sha256, boolean wantsEnhanced) {
		UUID readId = UUID.randomUUID();
		if (!wantsEnhanced) {
			return new ReadPlan(AiLevel.BASIC, readId, null);
		}
		EnhancedCvExtractionPort port = enhanced.getIfAvailable();
		if (!ai.cv().enabled() || port == null) {
			return new ReadPlan(AiLevel.BASIC, readId, "DISABLED");
		}
		if (sha256 != null && repository.hasClosedEnhancedRead(userId, sha256,
				clock.instant().minus(ai.reuseWindow()))) {
			return new ReadPlan(AiLevel.BASIC, readId, "ALREADY_READ");
		}
		QuotaStatus status = quota.check(userId, AiFunction.CV_READ);
		if (status.blockedBy() == QuotaStatus.Block.PERSONAL) {
			record(userId, readId, port.info(), AiUsageOutcome.BLOCKED_PERSONAL_QUOTA, 0, 0);
			throw CvErrors.aiQuotaReached(status.personalResets(), status.used(), status.limit());
		}
		if (status.blockedBy() == QuotaStatus.Block.GLOBAL) {
			record(userId, readId, port.info(), AiUsageOutcome.BLOCKED_GLOBAL_QUOTA, 0, 0);
			return new ReadPlan(AiLevel.BASIC, readId, "GLOBAL_LIMIT");
		}
		return new ReadPlan(AiLevel.ENHANCED, readId, null);
	}

	/** Qué niveles hay disponibles y el cupo de la persona (009-27, 009-29). No anota nada ni lee nada. */
	public UsageView usage(UUID userId) {
		QuotaStatus status = quota.check(userId, AiFunction.CV_READ);
		String reason = null;
		if (!enhancedAvailable()) {
			reason = "DISABLED";
		}
		else if (status.blockedBy() == QuotaStatus.Block.PERSONAL) {
			reason = "PERSONAL_LIMIT";
		}
		else if (status.blockedBy() == QuotaStatus.Block.GLOBAL) {
			reason = "GLOBAL_LIMIT";
		}
		return new UsageView(new UsageView.Basic(true), new UsageView.Enhanced(reason == null, reason, status.used(),
				status.limit(), status.personalResets()));
	}

	// ------------------------------------------------------------------------------------------ lectura

	/**
	 * Ejecuta una lectura (corre en el trabajo asíncrono). Con {@code ENHANCED} llama una vez a la IA; si falla, se
	 * conservan las sugerencias anteriores (si las hay) o se lee con las reglas. La lectura básica que falla lanza
	 * {@link ReadFailedException}.
	 */
	public ReadResult execute(UUID userId, UUID readId, AiLevel level, String reducedText, boolean hasPrevious) {
		String degradedReason = null;
		EnhancedCvExtractionPort port = enhanced.getIfAvailable();
		if (level == AiLevel.ENHANCED) {
			if (port == null || !ai.cv().enabled()) {
				degradedReason = "DISABLED";
			}
			else if (recorder.exists(readId)) {
				// Ya hubo un intento de esta lectura (un reintento del trabajo): no se vuelve a llamar (009-32).
				degradedReason = "ALREADY_ATTEMPTED";
			}
			else {
				TextLimiter.Limited limited = TextLimiter.limit(reducedText, ai.cv().maxInputChars());
				try {
					List<RawSuggestion> raw = withTimeout(() -> port.extract(limited.text(), catalog.all()),
							ai.cv().timeout().toMillis());
					List<SuggestionDraft> drafts = validator.validate(raw);
					record(userId, readId, port.info(), AiUsageOutcome.SUCCESS, limited.text().length(),
							outputSize(drafts));
					return new ReadResult(drafts, false, AiLevel.ENHANCED, port.info(), null, limited.truncated());
				}
				catch (RuntimeException failure) {
					// Falla de la plataforma, no de la persona: se anota y NO cuenta en el cupo.
					log.warn("La lectura mejorada falló ({}); se usa la lectura básica", failure.getClass().getSimpleName());
					record(userId, readId, port.info(), AiUsageOutcome.FAILED_PLATFORM, limited.text().length(), 0);
					degradedReason = "AI_FAILED";
				}
			}
			if (hasPrevious) {
				return ReadResult.keep(degradedReason);
			}
		}
		try {
			List<RawSuggestion> raw = withTimeout(() -> rules.extract(reducedText, catalog.all()),
					properties.analysisTimeout().toMillis());
			return new ReadResult(validator.validate(raw), false, AiLevel.BASIC, rules.info(), degradedReason, false);
		}
		catch (TimedOut e) {
			throw new ReadFailedException("TIMEOUT");
		}
		catch (RuntimeException e) {
			log.warn("La lectura básica falló ({})", e.getClass().getSimpleName());
			throw new ReadFailedException("EXTRACTION_ERROR");
		}
	}

	// ------------------------------------------------------------------------------------------ piezas

	private void record(UUID userId, UUID readId, AdapterInfo info, AiUsageOutcome outcome, int input, int output) {
		recorder.record(new AiUsageEvent(readId, userId, AiFunction.CV_READ, AiLevel.ENHANCED, info.name(),
				info.model(), outcome, input, output));
	}

	private static int outputSize(List<SuggestionDraft> drafts) {
		int size = 0;
		for (SuggestionDraft draft : drafts) {
			size += draft.payload().toString().length() + draft.evidence().length();
		}
		return size;
	}

	/** La llamada pasó del tiempo permitido. */
	private static final class TimedOut extends RuntimeException {

		private static final long serialVersionUID = 1L;

		TimedOut() {
			super("timeout", null, false, false);
		}

	}

	private <T> T withTimeout(Supplier<T> call, long millis) {
		Future<T> task = executor.submit(call::get);
		try {
			return task.get(millis, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			task.cancel(true);
			throw new TimedOut();
		}
		catch (InterruptedException e) {
			task.cancel(true);
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Lectura interrumpida");
		}
		catch (ExecutionException e) {
			Throwable cause = e.getCause();
			// Solo el tipo del fallo: el mensaje de una excepción podría traer contenido.
			throw new IllegalStateException("La lectura falló: " + cause.getClass().getSimpleName());
		}
	}

}
