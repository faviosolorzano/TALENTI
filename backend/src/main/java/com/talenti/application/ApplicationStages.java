package com.talenti.application;

import java.util.Optional;
import java.util.UUID;

/**
 * Interfaz pública de las etapas de una postulación para otros módulos (spec 012, sección 5): la usa practice para la
 * entrevista evaluada. Toda operación recibe el id de la persona: una postulación ajena es igual a una inexistente.
 * Cada cambio de etapa deja su {@code application_stage_event}. No devuelve datos personales.
 */
public interface ApplicationStages {

	/** Etapas de RN-06, con los mismos nombres que la API. */
	enum Stage {

		SUBMITTED, WITHDRAWN, EXAM_PENDING, EXAM_DONE, INTERVIEW_PENDING, INTERVIEW_DONE, EXPIRED, UNDER_REVIEW

	}

	/** Una postulación propia: su vacante y su etapa. */
	record Owned(UUID applicationId, UUID vacancyId, Stage stage) {
	}

	/** La postulación si es de esa persona; vacío si no existe o es ajena (no se distinguen). */
	Optional<Owned> findOwned(UUID applicationId, UUID userId);

	/** SUBMITTED a EXAM_PENDING, con evento e id de dueño (014-08). */
	boolean startExam(UUID applicationId, UUID userId);

	/** EXAM_PENDING a EXAM_DONE, con evento e id de dueño (014-13). */
	boolean completeExam(UUID applicationId, UUID userId);

	/** {@code SUBMITTED} a {@code INTERVIEW_PENDING}. Falso si la postulación ya no estaba enviada. */
	boolean startInterview(UUID applicationId, UUID userId);

	/** {@code INTERVIEW_PENDING} a {@code INTERVIEW_DONE}. Falso si la postulación ya no estaba en esa etapa. */
	boolean completeInterview(UUID applicationId, UUID userId);

}
