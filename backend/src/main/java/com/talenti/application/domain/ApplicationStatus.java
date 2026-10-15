package com.talenti.application.domain;

/**
 * Estados de una postulación (RN-06). Los ocho existen en el modelo, pero en el E2 ningún endpoint lleva a otro que no
 * sea {@code SUBMITTED} o {@code WITHDRAWN} (008-13); los demás los usará el E3.
 */
public enum ApplicationStatus {

	SUBMITTED,
	WITHDRAWN,
	EXAM_PENDING,
	EXAM_DONE,
	INTERVIEW_PENDING,
	INTERVIEW_DONE,
	EXPIRED,
	UNDER_REVIEW;

	/** Activa es toda postulación que no está retirada ni vencida (008-09): bloquea postular otra vez. */
	public boolean isActive() {
		return this != WITHDRAWN && this != EXPIRED;
	}

	/** Solo una postulación enviada se puede retirar; una retirada no se reabre (008-06, RN-06). */
	public boolean canWithdraw() {
		return this == SUBMITTED;
	}

	/** El estado al que lleva retirar; lanza si el estado actual no lo permite. */
	public ApplicationStatus withdraw() {
		if (!canWithdraw()) {
			throw new IllegalStateException("Solo se puede retirar una postulación enviada");
		}
		return WITHDRAWN;
	}

}
