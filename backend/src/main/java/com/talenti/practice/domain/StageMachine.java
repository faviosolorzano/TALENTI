package com.talenti.practice.domain;

import java.util.Map;
import java.util.Set;

/**
 * La máquina de etapas de la conversación en tiempo real (012-45). Solo acepta AVANCES: una pregunta del plan con
 * posición mayor que la anterior, como máximo {@code followUpsPerQuestion} repreguntas de la pregunta actual, y nunca
 * hacia atrás. Lo rechazado no cambia la etapa vigente: lo dicho sigue en la última etapa válida. Código puro.
 */
public final class StageMachine {

	/** Una etapa: fase y posición (solo en preguntas). */
	public record Stage(RealtimePhase phase, Integer position) {

		public boolean isQuestion() {
			return phase != null && phase.isQuestion();
		}

	}

	private final Set<Integer> plan;

	private final int followUpsPerQuestion;

	/**
	 * @param plan                 posiciones de las preguntas planificadas
	 * @param followUpsPerQuestion repreguntas permitidas por pregunta (1 por defecto)
	 */
	public StageMachine(Set<Integer> plan, int followUpsPerQuestion) {
		this.plan = Set.copyOf(plan);
		this.followUpsPerQuestion = followUpsPerQuestion;
	}

	/**
	 * Si se acepta pasar de {@code current} (nulo si aún no hay etapa) a {@code next}.
	 *
	 * @param lastQuestion posición de la última pregunta aceptada (0 si ninguna)
	 * @param followUps    repreguntas ya aceptadas por posición
	 */
	public boolean accepts(Stage current, Stage next, int lastQuestion, Map<Integer, Integer> followUps) {
		if (next == null || next.phase() == null) {
			return false;
		}
		RealtimePhase now = current == null ? null : current.phase();
		if (now == RealtimePhase.CLOSING) {
			return false;
		}
		return switch (next.phase()) {
			case GREETING -> now == null;
			case INTRO -> now == null || now == RealtimePhase.GREETING;
			case QUESTION -> next.position() != null && plan.contains(next.position()) && next.position() > lastQuestion
					&& now != RealtimePhase.CANDIDATE_QUESTIONS;
			case FOLLOW_UP -> next.position() != null && current != null && current.isQuestion()
					&& next.position().equals(current.position())
					&& followUps.getOrDefault(next.position(), 0) < followUpsPerQuestion;
			case CANDIDATE_QUESTIONS -> now != RealtimePhase.CANDIDATE_QUESTIONS;
			case CLOSING -> true;
		};
	}

}
