package com.talenti.cv.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.talenti.cv.AdapterInfo;
import com.talenti.cv.config.CvProperties;
import com.talenti.cv.domain.Fold;
import com.talenti.cv.domain.PersonalDataReducer;
import com.talenti.cv.persistence.AnalysisRecord;
import com.talenti.cv.persistence.CvRepository;
import com.talenti.cv.persistence.SuggestionRecord;
import com.talenti.cv.pdf.CvUnreadableException;
import com.talenti.cv.pdf.PdfTextExtractor;
import com.talenti.profile.ProfileEditor;
import com.talenti.profile.ProfileView;
import com.talenti.shared.ai.AiLevel;
import com.talenti.shared.audit.AuditEvent;
import com.talenti.shared.audit.AuditRecorder;
import com.talenti.shared.web.FieldViolation;
import com.talenti.shared.web.ValidationFailedException;

import tools.jackson.databind.JsonNode;

/**
 * Casos de uso del lector de CV (spec 009). Toda operación recibe el id de la persona que salió de la sesión; ninguna
 * acepta el de otra, y un análisis ajeno es igual a uno inexistente (404, 009-17). Nada del contenido del CV (texto,
 * evidencias, nombres) llega a los registros, a la auditoría ni a los errores: solo ids, tamaños y conteos (009-18).
 *
 * <p>El PDF se lee en la misma solicitud de subida (hace falta para validarlo, 009-05) y NO se guarda: solo se conserva
 * el texto ya reducido mientras el análisis siga abierto (009-10). Una lectura (básica o mejorada) corre aparte, en un
 * trabajo asíncrono (RNF-10); aquí solo se decide cuál y se encola, y la lectura propiamente dicha la hace
 * {@link CvReadService}.
 */
@Service
public class CvAnalysisService {

	private static final Logger log = LoggerFactory.getLogger(CvAnalysisService.class);

	private final CvRepository repository;

	private final PdfTextExtractor pdf;

	private final PersonalDataReducer reducer;

	private final CvReadService reads;

	private final ProfileEditor profile;

	private final AuditRecorder audit;

	private final TransactionTemplate transaction;

	private final CvProperties properties;

	private final Clock clock;

	private final ThreadPoolExecutor jobs;

	CvAnalysisService(CvRepository repository, PdfTextExtractor pdf, PersonalDataReducer reducer, CvReadService reads,
			ProfileEditor profile, AuditRecorder audit, PlatformTransactionManager transactionManager,
			CvProperties properties, Clock clock, ThreadPoolExecutor cvJobs) {
		this.repository = repository;
		this.pdf = pdf;
		this.reducer = reducer;
		this.reads = reads;
		this.profile = profile;
		this.audit = audit;
		this.transaction = new TransactionTemplate(transactionManager);
		this.properties = properties;
		this.clock = clock;
		this.jobs = cvJobs;
	}

	// ------------------------------------------------------------------------------------------ subir

	/**
	 * Valida el archivo, lo lee, reduce los datos innecesarios y deja el análisis esperando (202). Si la persona ya tenía
	 * el mismo archivo abierto, devuelve ese análisis sin leer de nuevo (009-26).
	 */
	public StartedRead upload(UUID userId, byte[] bytes, String contentType, String mode) {
		boolean wantsEnhanced = parseMode(mode);
		if (!isPdfType(contentType)) {
			throw CvErrors.invalidType();
		}
		if (bytes.length > properties.maxSize().toBytes()) {
			throw CvErrors.tooLarge();
		}
		if (!PdfTextExtractor.hasPdfSignature(bytes)) {
			// Mismo error que el resto de los PDF ilegibles; no queda nada guardado.
			throw new CvUnreadableException(CvUnreadableException.Reason.NOT_A_PDF);
		}
		String sha256 = sha256(bytes);

		Optional<AnalysisRecord> open = repository.findOpen(userId);
		if (open.isPresent()) {
			return sameOrConflict(open.get(), sha256);
		}
		Instant now = clock.instant();
		repository.discardFailed(userId, now);
		Instant since = now.minus(Duration.ofHours(24));
		if (repository.countCreatedSince(userId, since) >= properties.dailyLimit()) {
			long wait = repository.oldestCreatedSince(userId, since)
					.map(oldest -> Duration.between(now, oldest.plus(Duration.ofHours(24))).toSeconds() + 1)
					.orElse(60L);
			throw CvErrors.limitReached(wait);
		}

		String reduced = reducer.reduce(pdf.extract(bytes).text());
		ReadPlan plan = reads.plan(userId, sha256, wantsEnhanced);
		AdapterInfo planned = plan.level() == AiLevel.ENHANCED ? reads.enhancedInfo() : reads.basicInfo();

		UUID id = UUID.randomUUID();
		try {
			transaction.executeWithoutResult(status -> {
				repository.insert(id, userId, bytes.length, sha256, reduced, false, plan.readId(), planned.name(),
						planned.version(), planned.ruleSetVersion(), plan.degraded(), plan.degradedReason(), now,
						now.plus(properties.retention()));
				audit.record(new AuditEvent("CV_UPLOADED", userId, "cv_analysis", id,
						Map.of("sizeBytes", bytes.length, "adapter", planned.name(), "level", plan.level().name())));
			});
		}
		catch (DuplicateKeyException race) {
			// Dos subidas a la vez: la otra ganó el único análisis abierto.
			return repository.findOpen(userId).map(winner -> sameOrConflict(winner, sha256))
					.orElseThrow(CvErrors::inProgress);
		}
		submit(id, plan);
		return new StartedRead(id, "PENDING", false, plan.degraded(), plan.degradedReason());
	}

	private StartedRead sameOrConflict(AnalysisRecord open, String sha256) {
		if (open.fileSha256().equals(sha256)) {
			// El mismo archivo con el análisis abierto: se devuelve su resultado guardado sin leer de nuevo (009-26).
			repository.markReused(open.id());
			return new StartedRead(open.id(), open.status(), true, open.degraded(), open.degradedReason());
		}
		throw CvErrors.inProgress();
	}

	// ------------------------------------------------------------------------------------------ otra lectura

	/**
	 * "Leer mejor con IA" o "Volver a leer" sobre el análisis abierto, o el reintento de uno que falló (009-27,
	 * 009-32). Dos pulsaciones seguidas crean una sola lectura: la segunda recibe 409.
	 */
	public StartedRead requestRead(UUID userId, UUID analysisId, String mode) {
		boolean wantsEnhanced = parseMode(mode);
		AnalysisRecord analysis = repository.findOwned(analysisId, userId).orElseThrow(CvErrors::notFound);
		if (!isOpenOrFailed(analysis.status())) {
			throw CvErrors.notFound();
		}
		if (!Set.of("READY", "FAILED").contains(analysis.status())) {
			throw CvErrors.inProgress();
		}
		ReadPlan plan = reads.plan(userId, analysis.fileSha256(), wantsEnhanced);
		int updated;
		try {
			updated = repository.requestRead(analysisId, userId, plan.readId(), plan.degraded(), plan.degradedReason());
		}
		catch (DuplicateKeyException other) {
			throw CvErrors.inProgress();
		}
		if (updated == 0) {
			throw CvErrors.inProgress();
		}
		submit(analysisId, plan);
		return new StartedRead(analysisId, "PENDING", false, plan.degraded(), plan.degradedReason());
	}

	// ------------------------------------------------------------------------------------------ el trabajo

	private void submit(UUID analysisId, ReadPlan plan) {
		try {
			jobs.execute(() -> run(analysisId, plan.readId(), plan.level()));
		}
		catch (RejectedExecutionException busy) {
			repository.fail(analysisId, plan.readId(), "BUSY");
		}
	}

	/**
	 * El trabajo asíncrono de una lectura (PENDING a ANALYZING a READY o FAILED). Es seguro repetirlo: solo uno logra
	 * tomar la lectura, así un reintento no vuelve a llamar a la IA (009-32). Si el análisis se descartó mientras se
	 * leía, el resultado se desecha.
	 */
	public void run(UUID analysisId, UUID readId, AiLevel level) {
		try {
			if (repository.claim(analysisId, readId) == 0) {
				return;
			}
			AnalysisRecord analysis = repository.findForJob(analysisId).orElse(null);
			if (analysis == null || analysis.reducedText() == null) {
				repository.fail(analysisId, readId, "EXTRACTION_ERROR");
				return;
			}
			boolean hasPrevious = !repository.suggestions(analysisId).isEmpty();
			ReadResult result;
			try {
				result = reads.execute(analysis.userId(), readId, level, analysis.reducedText(), hasPrevious);
			}
			catch (ReadFailedException failed) {
				repository.fail(analysisId, readId, failed.reason());
				return;
			}
			if (result.keepPrevious()) {
				repository.keepPrevious(analysisId, readId, result.degraded());
				return;
			}
			AdapterInfo info = result.info();
			// Si la degradación se decidió al pedir la lectura (sin IA, sin cupo global, misma huella), se conserva.
			String degraded = result.degraded() != null ? result.degraded() : analysis.degradedReason();
			transaction.executeWithoutResult(status -> repository.complete(analysisId, readId, result.drafts(),
					result.level().name(), info.name(), info.version(), info.ruleSetVersion(), degraded != null, degraded,
					result.inputTruncated()));
		}
		catch (RuntimeException unexpected) {
			// Solo el tipo: nunca el mensaje ni contenido.
			log.error("El trabajo de lectura {} terminó con un error ({})", analysisId, unexpected.getClass().getName());
			repository.fail(analysisId, readId, "EXTRACTION_ERROR");
		}
	}

	// ------------------------------------------------------------------------------------------ consultar

	/** El análisis vigente de la persona (abierto, o el último fallido); vacío si no hay. Nunca lee ni llama a la IA. */
	public Optional<AnalysisView> current(UUID userId) {
		return repository.findCurrent(userId).map(analysis -> view(userId, analysis));
	}

	public UsageView usage(UUID userId) {
		return reads.usage(userId);
	}

	private AnalysisView view(UUID userId, AnalysisRecord analysis) {
		List<AnalysisView.SuggestionView> suggestions = new ArrayList<>();
		int already = 0;
		if ("READY".equals(analysis.status())) {
			ProfileView current = profile.current(userId);
			for (SuggestionRecord suggestion : repository.suggestions(analysis.id())) {
				Existing existing = existing(current, suggestion);
				if (existing.covered()) {
					already++;
				}
				else {
					suggestions.add(new AnalysisView.SuggestionView(suggestion.id(), suggestion.kind().name(),
							suggestion.field(), suggestion.payload(), suggestion.evidence(), existing.currentValue()));
				}
			}
		}
		return new AnalysisView(analysis.id(), analysis.status(), "ENHANCED".equals(analysis.level()) ? "AI" : "RULES",
				analysis.level(), analysis.reused(), analysis.degraded(), analysis.degradedReason(),
				analysis.createdAt(), analysis.expiresAt(), analysis.failureReason(), analysis.inputTruncated(),
				already, suggestions);
	}

	private record Existing(boolean covered, Object currentValue) {
	}

	/** Lo que el perfil ya tiene igual o mejor no se vuelve a sugerir (009-14). */
	private static Existing existing(ProfileView profile, SuggestionRecord suggestion) {
		JsonNode payload = suggestion.payload();
		switch (suggestion.kind()) {
			case SKILL -> {
				String slug = payload.path("slug").isString() ? payload.path("slug").asString() : null;
				if (slug == null) {
					return new Existing(false, null);
				}
				int wanted = payload.path("level").isInt() ? payload.path("level").intValue() : 1;
				for (ProfileView.SkillEntry entry : profile.skills()) {
					if (entry.slug().equals(slug)) {
						return entry.level() >= wanted ? new Existing(true, entry.level())
								: new Existing(false, entry.level());
					}
				}
				return new Existing(false, null);
			}
			case ACADEMIC -> {
				JsonNode value = payload.path("value");
				return switch (suggestion.field()) {
					case "CAREER" -> sameText(profile.academic().career(), value);
					case "INSTITUTE" -> sameText(profile.academic().institute(), value);
					default -> {
						Integer cycle = profile.academic().cycle();
						yield new Existing(cycle != null && value.isInt() && cycle == value.intValue(), cycle);
					}
				};
			}
			default -> {
				String name = Fold.of(payload.path("name").asString());
				for (ProfileView.ProjectEntry entry : profile.projects()) {
					if (Fold.of(entry.name()).equals(name)) {
						return new Existing(true, null);
					}
				}
				return new Existing(false, null);
			}
		}
	}

	private static Existing sameText(String current, JsonNode suggested) {
		boolean same = current != null && suggested.isString() && Fold.of(current).equals(Fold.of(suggested.asString()));
		return new Existing(same, current);
	}

	// ------------------------------------------------------------------------------------------ confirmar y descartar

	/**
	 * Aplica al perfil lo que la persona aceptó o editó, en una sola transacción (009-15). Lo omitido y lo descartado no
	 * se guarda. Cierra el análisis y borra el texto y las sugerencias.
	 */
	@Transactional
	public ProfileView confirm(UUID userId, UUID analysisId, List<DecisionInput> decisions) {
		AnalysisRecord analysis = repository.lockOwned(analysisId, userId).orElseThrow(CvErrors::notFound);
		if (!isOpenOrFailed(analysis.status())) {
			throw CvErrors.notFound();
		}
		if (!"READY".equals(analysis.status())) {
			throw CvErrors.notReady();
		}
		if (decisions == null) {
			throw new ValidationFailedException(List.of(FieldViolation.of("decisions", "required")));
		}
		if (decisions.size() > ConfirmationBuilder.MAX_DECISIONS) {
			throw new ValidationFailedException(
					List.of(FieldViolation.of("decisions", "max-items", ConfirmationBuilder.MAX_DECISIONS)));
		}
		ConfirmationBuilder built = new ConfirmationBuilder(decisions, repository.suggestions(analysisId));
		if (!built.violations().isEmpty()) {
			throw new ValidationFailedException(built.violations());
		}
		ProfileView result;
		if (built.hasChanges()) {
			try {
				result = profile.applyConfirmed(userId, built.changes());
			}
			catch (ValidationFailedException invalid) {
				throw new ValidationFailedException(built.remap(invalid.getViolations()));
			}
		}
		else {
			result = profile.current(userId);
		}
		repository.close(analysisId, userId, "CONFIRMED", clock.instant());
		audit.record(new AuditEvent("CV_ANALYSIS_CONFIRMED", userId, "cv_analysis", analysisId,
				Map.of("accepted", built.accepted(), "edited", built.edited(), "discarded", built.discarded())));
		return result;
	}

	/** Descartar o borrar mi CV: borra el texto y las sugerencias y deja el perfil como estaba (009-16). */
	@Transactional
	public void discard(UUID userId, UUID analysisId) {
		AnalysisRecord analysis = repository.lockOwned(analysisId, userId).orElseThrow(CvErrors::notFound);
		if (!isOpenOrFailed(analysis.status())
				|| !repository.close(analysisId, userId, "DISCARDED", clock.instant())) {
			throw CvErrors.notFound();
		}
		audit.record(new AuditEvent("CV_ANALYSIS_DISCARDED", userId, "cv_analysis", analysisId, Map.of()));
	}

	private static boolean isOpenOrFailed(String status) {
		return Set.of("PENDING", "ANALYZING", "READY", "FAILED").contains(status);
	}

	// ------------------------------------------------------------------------------------------ piezas

	/** {@code BASIC} (por defecto) o {@code ENHANCED}; otra cosa es 422. */
	private static boolean parseMode(String mode) {
		if (mode == null || mode.isBlank()) {
			return false;
		}
		return switch (mode.strip().toUpperCase(Locale.ROOT)) {
			case "BASIC" -> false;
			case "ENHANCED" -> true;
			default -> throw new ValidationFailedException(List.of(FieldViolation.of("mode", "invalid")));
		};
	}

	private static boolean isPdfType(String contentType) {
		if (contentType == null) {
			return false;
		}
		String type = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
		return type.equals("application/pdf");
	}

	private static String sha256(byte[] bytes) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 no disponible", e);
		}
	}

	/** Para que la API lo use como 404 si el id no es un UUID. */
	public static UUID parseId(String raw) {
		try {
			return UUID.fromString(raw);
		}
		catch (IllegalArgumentException | NullPointerException e) {
			throw CvErrors.notFound();
		}
	}

}
