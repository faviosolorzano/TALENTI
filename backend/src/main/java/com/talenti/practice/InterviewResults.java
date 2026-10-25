package com.talenti.practice;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Interfaz pública de practice para la futura cara de empresa (012-27): el resultado de la ENTREVISTA EVALUADA de una
 * postulación. Es lo ÚNICO que practice expone a otros módulos y nunca devuelve sesiones de práctica (privadas, T5,
 * 012-22). Trae hechos y evaluación por criterios con citas y versiones; no hay recomendación de contratar ni
 * descartar, ni ranking (principio 1). En el E2 no tiene ruta HTTP.
 */
public interface InterviewResults {

	/** Todos los intentos de entrevista de la postulación, del primero al último; vacío si no hubo entrevista. */
	List<Attempt> forApplication(UUID applicationId);

	/** Un criterio evaluado de una respuesta, con las citas literales que lo respaldan. */
	record CriterionResult(String criterion, int level, String comment, List<String> quotes) {

		@Override
		public String toString() {
			return "CriterionResult[" + criterion + "]";
		}

	}

	/**
	 * @param evaluationStatus {@code READY}, {@code PENDING} o {@code UNAVAILABLE}; nulo si la pregunta no se respondió
	 */
	record AnswerResult(int position, String question, String questionSource, String transcriptRaw,
			String transcriptFinal, boolean corrected, String inputMode, String evaluationStatus,
			List<CriterionResult> criteria) {

		@Override
		public String toString() {
			return "AnswerResult[position=" + position + "]";
		}

	}

	/**
	 * Resumen por criterio calculado por el código: promedio de los niveles de las respuestas evaluadas. Las preguntas
	 * sin respuesta se cuentan aparte y no bajan el promedio (principio 4).
	 */
	record CriterionSummary(String criterion, Double averageLevel, int evaluated) {
	}

	record Event(String kind, Instant occurredAt) {
	}

	record Versions(String llmAdapter, String llmModel, String sttModel, String ttsModel, String promptVersion,
			String rubricVersion, String bankVersion) {
	}

	record Attempt(UUID sessionId, int attempt, String status, String noticeVersion, Instant noticeAcceptedAt,
			Instant startedAt, Instant endedAt, List<AnswerResult> answers, int unanswered,
			List<CriterionSummary> summary, String generatedBy, Versions versions, List<Event> events) {

		@Override
		public String toString() {
			return "Attempt[sessionId=" + sessionId + ", status=" + status + "]";
		}

	}

}
