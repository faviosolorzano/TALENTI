package com.talenti.practice.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Cuerpos de las respuestas de DORA (spec 012, sección 6). Todo lo que generó DORA lleva {@code generatedBy}
 * ({@code AI}, {@code TEMPLATE} o {@code BANK}, principio 2). Ningún {@code toString} muestra contenido (012-33).
 */
public final class Views {

	private Views() {
	}

	public record Versions(String llmAdapter, String llmModel, String sttModel, String ttsModel, String ttsVoice,
			String promptVersion, String rubricVersion, String bankVersion, String realtimeModel, String realtimeVoice,
			String transcriptionModel) {
	}

	/**
	 * Una intervención de la conversación en tiempo real (012-44, 012-48). Las de DORA llevan {@code generatedBy = AI}
	 * y todas {@code reportedBy = CLIENT}: las reporta el navegador, solo para mostrar.
	 */
	public record Utterance(String speaker, String text, String phase, Integer position, String inputMode,
			String generatedBy, String reportedBy, Instant occurredAt) {

		@Override
		public String toString() {
			return "Utterance[speaker=" + speaker + ", phase=" + phase + "]";
		}

	}

	/** 012-54: el resumen de la sesión abierta de la persona (en el 409 {@code session-open} y en {@code /open}). */
	public record OpenSession(UUID id, String mode, String status, String channel, UUID applicationId) {
	}

	/** 012-39: la llamada abierta. {@code toString} no muestra el SDP (lleva direcciones IP). */
	public record RealtimeCall(UUID callId, String sdp, Instant endsAt, Instant warnAt, String startPhase,
			Integer startPosition, RealtimeEvents events) {

		@Override
		public String toString() {
			return "RealtimeCall[callId=" + callId + "]";
		}

	}

	/** Nombres del canal de datos y de los eventos del proveedor que el navegador escucha (configuración). */
	public record RealtimeEvents(String channel, String personTranscript, String doraTranscript, String speechStarted,
			String speechStopped, String functionCall, String responseDone) {
	}

	/** 012-44: cuántos eventos se guardaron, cuántos ya estaban y cuántos se rechazaron. */
	public record EventsResult(int accepted, int duplicates, int rejected) {
	}

	/** 012-53: la sesión tras colgar y la primera pregunta sin respuesta. */
	public record Hangup(Session session, Integer nextPosition) {
	}

	/** 012-38: si DORA usa OpenAI o simulados y si el tiempo real está disponible (con el motivo si no). */
	public record Dora(String provider, Realtime realtime) {
	}

	/** @param reason {@code DISABLED}, {@code NOT_CONFIGURED} o {@code SPEND_LIMIT}; nulo si está disponible */
	public record Realtime(boolean available, String reason) {
	}

	public record Criterion(String criterion, String name, int level, String comment, List<String> quotes) {

		@Override
		public String toString() {
			return "Criterion[" + criterion + "]";
		}

	}

	/** Retroalimentación de práctica (012-18, 012-19): siempre {@code generatedBy = AI} con sus versiones. */
	public record Feedback(String status, List<Criterion> criteria, Integer total, String generatedBy, String model,
			String promptVersion, String rubricVersion, boolean retryable) {

		@Override
		public String toString() {
			return "Feedback[status=" + status + "]";
		}

	}

	public record Answer(UUID turnId, int attempt, String inputMode, String transcriptRaw, String transcriptFinal,
			boolean corrected, Instant createdAt, Feedback feedback) {

		@Override
		public String toString() {
			return "Answer[turnId=" + turnId + "]";
		}

	}

	public record Question(int position, String text, String source, String generatedBy, String basisRef,
			Answer answer) {

		@Override
		public String toString() {
			return "Question[position=" + position + "]";
		}

	}

	/**
	 * @param channel      {@code TURN_BASED} o {@code REALTIME} (enmienda 2)
	 * @param endsAt       fin decidido por el servidor para el tiempo real; nulo por turnos
	 * @param conversation lo dicho en tiempo real (persona y DORA, rotulada IA), en orden; vacío por turnos
	 */
	public record Session(UUID id, String mode, String basis, UUID vacancyId, UUID applicationId, String topic,
			int questionCount, boolean voice, String status, int attempt, List<String> degraded, Instant createdAt,
			Instant startedAt, Instant endedAt, Integer nextPosition, int answered, List<Question> questions,
			Versions versions, String channel, Instant endsAt, List<Utterance> conversation) {

		@Override
		public String toString() {
			return "Session[id=" + id + ", status=" + status + "]";
		}

	}

	public record SessionSummary(UUID id, Instant createdAt, String basis, UUID vacancyId, String topic, String status,
			int questionCount, int answered) {

		@Override
		public String toString() {
			return "SessionSummary[id=" + id + "]";
		}

	}

	public record SessionPage(List<SessionSummary> items, int page, int size, int total, int totalPages) {
	}

	public record Turn(UUID turnId, int position, int attempt, String inputMode, String transcriptRaw,
			String transcriptFinal, boolean corrected, String feedbackStatus) {

		@Override
		public String toString() {
			return "Turn[turnId=" + turnId + "]";
		}

	}

	// ------------------------------------------------------------------------------------------ opciones

	public record Topic(String slug, String name) {

		@Override
		public String toString() {
			return "Topic[" + slug + "]";
		}

	}

	public record VacancyBasis(UUID id, String title, String company, List<Topic> topics) {

		@Override
		public String toString() {
			return "VacancyBasis[id=" + id + "]";
		}

	}

	public record BasisOption(String basis, boolean available, List<VacancyBasis> vacancies) {
	}

	public record Quota(int used, int limit, Instant resetsAt) {
	}

	public record Notice(String version, String text) {

		@Override
		public String toString() {
			return "Notice[version=" + version + "]";
		}

	}

	/**
	 * @param topics temas del perfil (habilidades confirmadas); los de cada vacante van dentro de su base
	 */
	public record Options(List<BasisOption> bases, List<Topic> topics, int maxQuestions, Quota quota,
			boolean voiceAvailable, Notice interviewNotice, Dora dora) {

		@Override
		public String toString() {
			return "Options[...]";
		}

	}

	// ------------------------------------------------------------------------------------------ entrevista

	/** Lo que ve el estudiante de su entrevista: preguntas y transcripciones, nunca los niveles (duda 7). */
	public record InterviewQuestion(int position, String text, String generatedBy, String transcriptRaw,
			String transcriptFinal) {

		@Override
		public String toString() {
			return "InterviewQuestion[position=" + position + "]";
		}

	}

	public record InterviewView(UUID applicationId, UUID sessionId, String status, int attempt, int maxAttempts,
			boolean canRetry, String noticeVersion, Instant noticeAcceptedAt, Integer nextPosition,
			List<InterviewQuestion> questions, String channel, List<Utterance> conversation) {

		@Override
		public String toString() {
			return "InterviewView[sessionId=" + sessionId + "]";
		}

	}

	public record Latency(Long p50, Long p95, int count) {
	}

}
