package com.talenti.application.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Estados de una postulación (RN-06) y TODAS sus transiciones permitidas, en un solo lugar (012-37): la tabla
 * {@link #ALLOWED}. Sumar el examen (spec 014: {@code SUBMITTED → EXAM_PENDING → EXAM_DONE}) ya está previsto aquí y
 * no cambia la entrevista.
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

	/**
	 * Transiciones permitidas (RN-06 con las enmiendas de la spec 012: duda 8, 012-35 y 012-36). Retirar se permite
	 * desde enviada, examen pendiente o hecho y entrevista pendiente o hecha; una retirada no se reabre.
	 */
	private static final Map<ApplicationStatus, Set<ApplicationStatus>> ALLOWED = Map.of(
			SUBMITTED, EnumSet.of(WITHDRAWN, EXAM_PENDING, INTERVIEW_PENDING),
			EXAM_PENDING, EnumSet.of(WITHDRAWN, EXAM_DONE),
			EXAM_DONE, EnumSet.of(WITHDRAWN, INTERVIEW_PENDING),
			INTERVIEW_PENDING, EnumSet.of(WITHDRAWN, INTERVIEW_DONE),
			INTERVIEW_DONE, EnumSet.of(WITHDRAWN));

	/** Activa es toda postulación que no está retirada ni vencida (008-09): bloquea postular otra vez. */
	public boolean isActive() {
		return this != WITHDRAWN && this != EXPIRED;
	}

	/** Si se puede pasar de este estado a {@code target}. */
	public boolean canMoveTo(ApplicationStatus target) {
		return ALLOWED.getOrDefault(this, Set.of()).contains(target);
	}

	/** Se puede retirar desde SUBMITTED, EXAM_PENDING, EXAM_DONE, INTERVIEW_PENDING e INTERVIEW_DONE (012-36). */
	public boolean canWithdraw() {
		return canMoveTo(WITHDRAWN);
	}

	/** El estado al que lleva retirar; lanza si el estado actual no lo permite. */
	public ApplicationStatus withdraw() {
		if (!canWithdraw()) {
			throw new IllegalStateException("Esta postulación no se puede retirar en su estado actual");
		}
		return WITHDRAWN;
	}

}
