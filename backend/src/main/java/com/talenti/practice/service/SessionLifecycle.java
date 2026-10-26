package com.talenti.practice.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.talenti.application.ApplicationStages;
import com.talenti.practice.config.DoraProperties;
import com.talenti.practice.domain.SessionMode;
import com.talenti.practice.domain.SessionStatus;
import com.talenti.practice.persistence.PracticeRepository;
import com.talenti.practice.persistence.PracticeRepository.SessionRow;
import com.talenti.practice.persistence.PracticeRepository.TurnRow;
import com.talenti.shared.ai.AiUsageOutcome;
import com.talenti.shared.events.ApplicationWithdrawn;

import tools.jackson.databind.json.JsonMapper;

/**
 * Cierre de las sesiones (012-14, 012-25, 012-26): por la persona, por inactividad, por un reinicio del servidor o por
 * el retiro de la postulación. Al cerrar una entrevista COMPLETADA, la postulación pasa a {@code INTERVIEW_DONE} y se
 * evalúa cada respuesta en segundo plano. Deja los hechos de la entrevista en {@code interview_event}, sin contenido.
 * Una práctica nunca cambia una postulación (012-22).
 */
@Service
public class SessionLifecycle {

	private static final Logger log = LoggerFactory.getLogger(SessionLifecycle.class);

	private final PracticeRepository repository;

	private final LiveSessions live;

	private final ApplicationStages stages;

	private final FeedbackService feedback;

	private final UsageMeter usage;

	private final ExecutorService executor;

	private final DoraProperties properties;

	private final JsonMapper mapper;

	private final Clock clock;

	SessionLifecycle(PracticeRepository repository, LiveSessions live, ApplicationStages stages, FeedbackService feedback,
			UsageMeter usage, @Qualifier("doraExecutor") ExecutorService executor, DoraProperties properties,
			JsonMapper mapper, Clock clock) {
		this.repository = repository;
		this.live = live;
		this.stages = stages;
		this.feedback = feedback;
		this.usage = usage;
		this.executor = executor;
		this.properties = properties;
		this.mapper = mapper;
		this.clock = clock;
	}

	/** Un hecho de la entrevista (solo inserciones; datos sin contenido). En la práctica no se anota nada. */
	public void event(SessionRow session, String kind, Map<String, Object> data) {
		if (session.mode() == SessionMode.INTERVIEW) {
			repository.addEvent(session.id(), kind, mapper.writeValueAsString(data), clock.instant());
		}
	}

	/**
	 * Cierra la sesión si sigue abierta. Devuelve falso si ya estaba cerrada.
	 *
	 * @param status {@code COMPLETED}, {@code ABANDONED} o {@code FAILED}
	 */
	public boolean close(SessionRow session, SessionStatus status) {
		if (!repository.close(session.id(), status, clock.instant())) {
			return false;
		}
		live.forget(session.id());
		switch (status) {
			case COMPLETED -> {
				event(session, "FINISHED", Map.of());
				if (session.mode() == SessionMode.INTERVIEW) {
					stages.completeInterview(session.applicationId(), session.userId());
					executor.execute(() -> evaluateInterview(session));
				}
			}
			case ABANDONED -> event(session, "ABANDONED", Map.of());
			case FAILED -> {
				event(session, "PLATFORM_FAILURE", Map.of());
				// Compensa el uso anterior sin borrarlo; reintentar la compensación no devuelve el cupo dos veces.
				usage.sessionUsed(session.id(), session.userId(), session.mode(), AiUsageOutcome.FAILED_PLATFORM);
			}
			default -> {
			}
		}
		return true;
	}

	/** 012-25: cada respuesta vigente de la entrevista se evalúa con la rúbrica, sin el nombre de la persona. */
	void evaluateInterview(SessionRow session) {
		for (TurnRow turn : SessionViews.latestByPosition(repository.turns(session.id())).values()) {
			feedback.schedule(session.id(), turn.id(), session.userId(), SessionMode.INTERVIEW);
		}
	}

	/** 012-14: un reinicio deja las sesiones abiertas en {@code FAILED} (no se atribuye a la persona). */
	@EventListener(ApplicationReadyEvent.class)
	public void recoverAfterRestart() {
		int failed = 0;
		for (SessionRow session : repository.allOpen()) {
			if (!live.isBusy(session.id()) && close(session, SessionStatus.FAILED)) {
				failed++;
			}
		}
		if (failed > 0) {
			log.info("Sesiones con DORA cerradas como FAILED tras el reinicio: {}", failed);
		}
	}

	/**
	 * 012-14: sin actividad por {@code inactivity-timeout}, la sesión pasa a {@code ABANDONED}; una práctica con todas
	 * sus preguntas respondidas, a {@code COMPLETED} (terminó).
	 */
	@Scheduled(fixedDelayString = "${talenti.dora.sweep-interval:PT1M}", initialDelayString = "${talenti.dora.sweep-interval:PT1M}")
	public void sweepInactive() {
		Instant cutoff = clock.instant().minus(properties.inactivityTimeout());
		List<SessionRow> idle = repository.openIdleSince(cutoff);
		for (SessionRow session : idle) {
			if (live.isBusy(session.id())) {
				continue;
			}
			boolean allAnswered = session.mode() == SessionMode.PRACTICE
					&& SessionViews.nextPosition(session, repository.turns(session.id())) == null;
			close(session, allAnswered ? SessionStatus.COMPLETED : SessionStatus.ABANDONED);
		}
	}

	/** Sección 4: retirar la postulación con la entrevista en curso la deja en {@code ABANDONED}. */
	@EventListener
	public void onApplicationWithdrawn(ApplicationWithdrawn withdrawn) {
		for (SessionRow session : repository.openForApplication(withdrawn.applicationId())) {
			close(session, SessionStatus.ABANDONED);
		}
	}

}
