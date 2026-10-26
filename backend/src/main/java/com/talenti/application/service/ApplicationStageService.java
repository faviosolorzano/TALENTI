package com.talenti.application.service;

import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.talenti.application.ApplicationStages;
import com.talenti.application.domain.ApplicationStatus;
import com.talenti.application.persistence.ApplicationRepository;
import com.talenti.shared.audit.AuditEvent;
import com.talenti.shared.audit.AuditRecorder;

/**
 * Implementación de {@link ApplicationStages} (spec 012): los cambios de etapa de la entrevista evaluada. Cada cambio
 * guarda la fila, su evento de historial y la auditoría en una sola transacción. Solo ids en la auditoría.
 */
@Service
class ApplicationStageService implements ApplicationStages {

	private final ApplicationRepository repository;

	private final AuditRecorder audit;

	private final Clock clock;

	ApplicationStageService(ApplicationRepository repository, AuditRecorder audit, Clock clock) {
		this.repository = repository;
		this.audit = audit;
		this.clock = clock;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Owned> findOwned(UUID applicationId, UUID userId) {
		return repository.findOwned(applicationId, userId)
				.map(row -> new Owned(row.id(), row.vacancyId(), Stage.valueOf(row.status().name())));
	}

	/** 012-35: desde {@code SUBMITTED} o {@code EXAM_DONE}; en cualquier otro estado no cambia nada. */
	@Override
	@Transactional
	public boolean startExam(UUID applicationId, UUID userId) {
		return move(applicationId, userId, ApplicationStatus.EXAM_PENDING, "APPLICATION_EXAM_PENDING");
	}

	@Override
	@Transactional
	public boolean completeExam(UUID applicationId, UUID userId) {
		return move(applicationId, userId, ApplicationStatus.EXAM_DONE, "APPLICATION_EXAM_DONE");
	}

	/** 012-35: desde SUBMITTED o EXAM_DONE. */
	@Override
	@Transactional
	public boolean startInterview(UUID applicationId, UUID userId) {
		return move(applicationId, userId, ApplicationStatus.INTERVIEW_PENDING, "APPLICATION_INTERVIEW_PENDING");
	}

	@Override
	@Transactional
	public boolean completeInterview(UUID applicationId, UUID userId) {
		return move(applicationId, userId, ApplicationStatus.INTERVIEW_DONE, "APPLICATION_INTERVIEW_DONE");
	}

	/**
	 * Único camino de los cambios de etapa pedidos por otros módulos (012-37): la regla de qué se permite vive en
	 * {@link ApplicationStatus#canMoveTo}. La spec 014 sumará aquí startExam y completeExam sin tocar lo anterior.
	 */
	private boolean move(UUID applicationId, UUID userId, ApplicationStatus to, String action) {
		ApplicationStatus from = repository.findOwned(applicationId, userId).map(row -> row.status()).orElse(null);
		if (from == null || !from.canMoveTo(to)) {
			return false;
		}
		boolean moved = repository.transition(applicationId, userId, from, to, clock.instant());
		if (moved) {
			audit.record(new AuditEvent(action, userId, "application", applicationId, Map.of()));
		}
		return moved;
	}

}
