package com.talenti.practice.domain;

/**
 * Etapas de la conversación en tiempo real (012-45): {@code GREETING → INTRO → QUESTION(p) [FOLLOW_UP(p)] →
 * CANDIDATE_QUESTIONS → CLOSING}. Solo {@code QUESTION} y {@code FOLLOW_UP} llevan posición y se califican.
 */
public enum RealtimePhase {

	GREETING,
	INTRO,
	QUESTION,
	FOLLOW_UP,
	CANDIDATE_QUESTIONS,
	CLOSING;

	/** Etapa de una pregunta del plan (la respuesta se califica). */
	public boolean isQuestion() {
		return this == QUESTION || this == FOLLOW_UP;
	}

}
