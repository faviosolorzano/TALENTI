package com.talenti.practice.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.talenti.practice.domain.Basis;
import com.talenti.practice.domain.InputMode;
import com.talenti.practice.domain.QuestionSource;
import com.talenti.practice.domain.SessionMode;
import com.talenti.practice.domain.SessionStatus;

/**
 * Único lugar del módulo que toca sus tablas: {@code practice_session}, {@code practice_question},
 * {@code practice_turn}, {@code practice_feedback}, {@code interview_event}, {@code realtime_call} y
 * {@code practice_utterance} (principio 12). Nunca guarda audio. Las
 * consultas por persona filtran por {@code user_id}: una sesión ajena es igual a una inexistente (012-32).
 */
@Repository
public class PracticeRepository {

	// ------------------------------------------------------------------------------------------ filas

	public record SessionRow(UUID id, UUID userId, SessionMode mode, Basis basis, UUID vacancyId, UUID applicationId,
			String topic, int questionCount, boolean voiceEnabled, SessionStatus status, int attempt,
			String noticeVersion, Instant noticeAcceptedAt, List<String> degradedReasons, String llmAdapter,
			String llmModel, String sttModel, String ttsModel, String ttsVoice, String promptVersion,
			String rubricVersion, String bankVersion, Instant createdAt, Instant startedAt, Instant endedAt,
			Instant lastActivityAt, String channel, String realtimeModel, String realtimeVoice,
			String transcriptionModel, Instant endsAt) {

		public SessionRow {
			degradedReasons = List.copyOf(degradedReasons);
		}

		@Override
		public String toString() {
			return "SessionRow[id=" + id + ", mode=" + mode + ", status=" + status + "]";
		}

	}

	public record QuestionRow(UUID id, UUID sessionId, int position, String text, QuestionSource source,
			String basisRef) {

		@Override
		public String toString() {
			return "QuestionRow[id=" + id + ", position=" + position + "]";
		}

	}

	public record TurnRow(UUID id, UUID questionId, UUID sessionId, int position, UUID clientTurnId, int attempt,
			InputMode inputMode, String transcriptRaw, String transcriptFinal, Integer audioMs, Integer sttMs,
			Integer firstAudioMs, Integer totalMs, String bridgeSource, Instant correctedAt, Instant createdAt) {

		@Override
		public String toString() {
			return "TurnRow[id=" + id + ", position=" + position + ", attempt=" + attempt + "]";
		}

	}

	public record FeedbackRow(UUID turnId, String status, String criteriaJson, Integer total, String model,
			String promptVersion, String rubricVersion, Instant createdAt) {

		@Override
		public String toString() {
			return "FeedbackRow[turnId=" + turnId + ", status=" + status + "]";
		}

	}

	public record EventRow(String kind, Instant occurredAt, String dataJson) {
	}

	/** Lo necesario para crear una sesión. */
	public record NewSession(UUID id, UUID userId, SessionMode mode, Basis basis, UUID vacancyId, UUID applicationId,
			String topic, int questionCount, boolean voiceEnabled, int attempt, String noticeVersion,
			Instant noticeAcceptedAt, String llmAdapter, String llmModel, String sttModel, String ttsModel,
			String ttsVoice, String promptVersion, String rubricVersion, String bankVersion) {

		@Override
		public String toString() {
			return "NewSession[id=" + id + ", mode=" + mode + "]";
		}

	}

	public record NewQuestion(int position, String text, QuestionSource source, String basisRef) {

		@Override
		public String toString() {
			return "NewQuestion[position=" + position + "]";
		}

	}

	private static final String SESSION_COLUMNS = """
			id, user_id, mode, basis, vacancy_id, application_id, topic, question_count, voice_enabled, status, attempt,
			notice_version, notice_accepted_at, degraded_reasons, llm_adapter, llm_model, stt_model, tts_model,
			tts_voice, prompt_version, rubric_version, bank_version, created_at, started_at, ended_at, last_activity_at,
			channel, realtime_model, realtime_voice, transcription_model, ends_at""";

	private static final String TURN_COLUMNS = """
			t.id, t.question_id, q.session_id, q.position, t.client_turn_id, t.attempt, t.input_mode, t.transcript_raw,
			t.transcript_final, t.audio_ms, t.stt_ms, t.first_audio_ms, t.total_ms, t.bridge_source, t.corrected_at,
			t.created_at""";

	private final JdbcClient jdbc;

	PracticeRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private static OffsetDateTime utc(Instant instant) {
		return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static Integer integer(ResultSet rs, String column) throws SQLException {
		int value = rs.getInt(column);
		return rs.wasNull() ? null : value;
	}

	private static SessionRow session(ResultSet rs, int n) throws SQLException {
		Array array = rs.getArray("degraded_reasons");
		List<String> degraded = new ArrayList<>();
		if (array != null) {
			for (Object item : (Object[]) array.getArray()) {
				degraded.add(String.valueOf(item));
			}
		}
		return new SessionRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				SessionMode.valueOf(rs.getString("mode")), Basis.valueOf(rs.getString("basis")),
				rs.getObject("vacancy_id", UUID.class), rs.getObject("application_id", UUID.class),
				rs.getString("topic"), rs.getInt("question_count"), rs.getBoolean("voice_enabled"),
				SessionStatus.valueOf(rs.getString("status")), rs.getInt("attempt"), rs.getString("notice_version"),
				instant(rs, "notice_accepted_at"), degraded, rs.getString("llm_adapter"), rs.getString("llm_model"),
				rs.getString("stt_model"), rs.getString("tts_model"), rs.getString("tts_voice"),
				rs.getString("prompt_version"), rs.getString("rubric_version"), rs.getString("bank_version"),
				instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "ended_at"),
				instant(rs, "last_activity_at"), rs.getString("channel"), rs.getString("realtime_model"),
				rs.getString("realtime_voice"), rs.getString("transcription_model"), instant(rs, "ends_at"));
	}

	private static QuestionRow question(ResultSet rs, int n) throws SQLException {
		return new QuestionRow(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class),
				rs.getInt("position"), rs.getString("text"), QuestionSource.valueOf(rs.getString("source")),
				rs.getString("basis_ref"));
	}

	private static TurnRow turn(ResultSet rs, int n) throws SQLException {
		return new TurnRow(rs.getObject("id", UUID.class), rs.getObject("question_id", UUID.class),
				rs.getObject("session_id", UUID.class), rs.getInt("position"), rs.getObject("client_turn_id", UUID.class),
				rs.getInt("attempt"), InputMode.valueOf(rs.getString("input_mode")), rs.getString("transcript_raw"),
				rs.getString("transcript_final"), integer(rs, "audio_ms"), integer(rs, "stt_ms"),
				integer(rs, "first_audio_ms"), integer(rs, "total_ms"), rs.getString("bridge_source"),
				instant(rs, "corrected_at"), instant(rs, "created_at"));
	}

	private static FeedbackRow feedback(ResultSet rs, int n) throws SQLException {
		return new FeedbackRow(rs.getObject("turn_id", UUID.class), rs.getString("status"), rs.getString("criteria"),
				integer(rs, "total"), rs.getString("model"), rs.getString("prompt_version"),
				rs.getString("rubric_version"), instant(rs, "created_at"));
	}

	// ------------------------------------------------------------------------------------------ sesiones

	/**
	 * Crea la sesión con sus preguntas.
	 *
	 * @throws org.springframework.dao.DuplicateKeyException si la persona ya tiene una sesión abierta
	 */
	public void insertSession(NewSession s, List<NewQuestion> questions, Instant now) {
		jdbc.sql("""
				INSERT INTO practice_session (id, user_id, mode, basis, vacancy_id, application_id, topic, question_count,
				    voice_enabled, status, attempt, notice_version, notice_accepted_at, llm_adapter, llm_model, stt_model,
				    tts_model, tts_voice, prompt_version, rubric_version, bank_version, created_at, last_activity_at)
				VALUES (:id, :userId, :mode, :basis, :vacancyId, :applicationId, :topic, :count, :voice, 'CREATED',
				    :attempt, :noticeVersion, :noticeAt, :adapter, :llmModel, :sttModel, :ttsModel, :ttsVoice,
				    :promptVersion, :rubricVersion, :bankVersion, :now, :now)""")
				.param("id", s.id()).param("userId", s.userId()).param("mode", s.mode().name())
				.param("basis", s.basis().name()).param("vacancyId", s.vacancyId())
				.param("applicationId", s.applicationId()).param("topic", s.topic()).param("count", s.questionCount())
				.param("voice", s.voiceEnabled()).param("attempt", s.attempt()).param("noticeVersion", s.noticeVersion())
				.param("noticeAt", utc(s.noticeAcceptedAt())).param("adapter", s.llmAdapter())
				.param("llmModel", s.llmModel()).param("sttModel", s.sttModel()).param("ttsModel", s.ttsModel())
				.param("ttsVoice", s.ttsVoice()).param("promptVersion", s.promptVersion())
				.param("rubricVersion", s.rubricVersion()).param("bankVersion", s.bankVersion())
				.param("now", utc(now)).update();
		for (NewQuestion q : questions) {
			jdbc.sql("""
					INSERT INTO practice_question (id, session_id, position, text, source, basis_ref)
					VALUES (:id, :sessionId, :position, :text, :source, :basisRef)""")
					.param("id", UUID.randomUUID()).param("sessionId", s.id()).param("position", q.position())
					.param("text", q.text()).param("source", q.source().name()).param("basisRef", q.basisRef())
					.update();
		}
	}

	public Optional<SessionRow> findOwned(UUID id, UUID userId) {
		return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM practice_session WHERE id = :id AND user_id = :userId")
				.param("id", id).param("userId", userId).query(PracticeRepository::session).optional();
	}

	public Optional<SessionRow> findById(UUID id) {
		return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM practice_session WHERE id = :id").param("id", id)
				.query(PracticeRepository::session).optional();
	}

	public Optional<SessionRow> findOpen(UUID userId) {
		return jdbc.sql("SELECT " + SESSION_COLUMNS
				+ " FROM practice_session WHERE user_id = :userId AND status IN ('CREATED', 'IN_PROGRESS')")
				.param("userId", userId).query(PracticeRepository::session).optional();
	}

	public List<SessionRow> listPractice(UUID userId, int limit, int offset) {
		return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM practice_session WHERE user_id = :userId AND mode = "
				+ "'PRACTICE' ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset")
				.param("userId", userId).param("limit", limit).param("offset", offset)
				.query(PracticeRepository::session).list();
	}

	public int countPractice(UUID userId) {
		return jdbc.sql("SELECT count(*) FROM practice_session WHERE user_id = :userId AND mode = 'PRACTICE'")
				.param("userId", userId).query(Integer.class).single();
	}

	/** Borra la práctica con todo lo asociado (en cascada). Solo sesiones de práctica. */
	public boolean deletePractice(UUID id, UUID userId) {
		return jdbc.sql("DELETE FROM practice_session WHERE id = :id AND user_id = :userId AND mode = 'PRACTICE'")
				.param("id", id).param("userId", userId).update() == 1;
	}

	/** CREATED a IN_PROGRESS. */
	public boolean markStarted(UUID id, Instant now) {
		return jdbc.sql("""
				UPDATE practice_session SET status = 'IN_PROGRESS', started_at = coalesce(started_at, :now),
				    last_activity_at = :now
				WHERE id = :id AND status IN ('CREATED', 'IN_PROGRESS')""")
				.param("id", id).param("now", utc(now)).update() == 1;
	}

	public void touch(UUID id, Instant now) {
		jdbc.sql("UPDATE practice_session SET last_activity_at = :now WHERE id = :id").param("id", id)
				.param("now", utc(now)).update();
	}

	/** Cierra la sesión si sigue abierta. Devuelve falso si ya estaba cerrada (otro cierre llegó antes). */
	public boolean close(UUID id, SessionStatus status, Instant now) {
		return jdbc.sql("""
				UPDATE practice_session SET status = :status, ended_at = :now, last_activity_at = :now
				WHERE id = :id AND status IN ('CREATED', 'IN_PROGRESS')""")
				.param("id", id).param("status", status.name()).param("now", utc(now)).update() == 1;
	}

	public void addDegraded(UUID id, String reason) {
		jdbc.sql("""
				UPDATE practice_session SET degraded_reasons = array_append(degraded_reasons, CAST(:reason AS text))
				WHERE id = :id AND NOT (CAST(:reason AS text) = ANY (degraded_reasons))""")
				.param("id", id).param("reason", reason).update();
	}

	public List<SessionRow> openIdleSince(Instant cutoff) {
		return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM practice_session "
				+ "WHERE status IN ('CREATED', 'IN_PROGRESS') AND last_activity_at < :cutoff")
				.param("cutoff", utc(cutoff)).query(PracticeRepository::session).list();
	}

	public List<SessionRow> allOpen() {
		return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM practice_session WHERE status IN ('CREATED', 'IN_PROGRESS')")
				.query(PracticeRepository::session).list();
	}

	public List<SessionRow> openForApplication(UUID applicationId) {
		return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM practice_session "
				+ "WHERE application_id = :applicationId AND status IN ('CREATED', 'IN_PROGRESS')")
				.param("applicationId", applicationId).query(PracticeRepository::session).list();
	}

	/** Los intentos de entrevista de una postulación, del primero al último. */
	public List<SessionRow> interviews(UUID applicationId) {
		return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM practice_session "
				+ "WHERE application_id = :applicationId AND mode = 'INTERVIEW' ORDER BY attempt, created_at")
				.param("applicationId", applicationId).query(PracticeRepository::session).list();
	}

	// ------------------------------------------------------------------------------------------ preguntas y turnos

	public List<QuestionRow> questions(UUID sessionId) {
		return jdbc.sql("SELECT id, session_id, position, text, source, basis_ref FROM practice_question "
				+ "WHERE session_id = :sessionId ORDER BY position").param("sessionId", sessionId)
				.query(PracticeRepository::question).list();
	}

	/** Todos los turnos de la sesión (todos los intentos), por pregunta y por intento. */
	public List<TurnRow> turns(UUID sessionId) {
		return jdbc.sql("SELECT " + TURN_COLUMNS + " FROM practice_turn t JOIN practice_question q ON q.id = t.question_id "
				+ "WHERE q.session_id = :sessionId ORDER BY q.position, t.attempt").param("sessionId", sessionId)
				.query(PracticeRepository::turn).list();
	}

	public Optional<TurnRow> findTurnByClientId(UUID clientTurnId) {
		return jdbc.sql("SELECT " + TURN_COLUMNS + " FROM practice_turn t JOIN practice_question q ON q.id = t.question_id "
				+ "WHERE t.client_turn_id = :clientTurnId").param("clientTurnId", clientTurnId)
				.query(PracticeRepository::turn).optional();
	}

	public Optional<TurnRow> findTurn(UUID turnId, UUID sessionId) {
		return jdbc.sql("SELECT " + TURN_COLUMNS + " FROM practice_turn t JOIN practice_question q ON q.id = t.question_id "
				+ "WHERE t.id = :turnId AND q.session_id = :sessionId").param("turnId", turnId)
				.param("sessionId", sessionId).query(PracticeRepository::turn).optional();
	}

	/**
	 * Guarda la respuesta apenas hay transcripción (012-12): si la red se corta después, el turno queda guardado.
	 *
	 * @throws org.springframework.dao.DuplicateKeyException si el {@code clientTurnId} o el intento ya existen
	 */
	public void insertTurn(UUID id, UUID questionId, UUID clientTurnId, int attempt, InputMode mode, String transcript,
			Integer audioMs, Integer sttMs, Instant now) {
		jdbc.sql("""
				INSERT INTO practice_turn (id, question_id, client_turn_id, attempt, input_mode, transcript_raw,
				    transcript_final, audio_ms, stt_ms, created_at)
				VALUES (:id, :questionId, :clientTurnId, :attempt, :mode, :text, :text, :audioMs, :sttMs, :now)""")
				.param("id", id).param("questionId", questionId).param("clientTurnId", clientTurnId)
				.param("attempt", attempt).param("mode", mode.name()).param("text", transcript)
				.param("audioMs", audioMs).param("sttMs", sttMs).param("now", utc(now)).update();
	}

	public void updateTurnTimings(UUID turnId, Integer firstAudioMs, Integer totalMs, String bridgeSource) {
		jdbc.sql("""
				UPDATE practice_turn SET first_audio_ms = :firstAudioMs, total_ms = :totalMs, bridge_source = :source
				WHERE id = :id""")
				.param("id", turnId).param("firstAudioMs", firstAudioMs).param("totalMs", totalMs)
				.param("source", bridgeSource).update();
	}

	public void correctTranscript(UUID turnId, String transcript, Instant now) {
		jdbc.sql("UPDATE practice_turn SET transcript_final = :text, corrected_at = :now WHERE id = :id")
				.param("id", turnId).param("text", transcript).param("now", utc(now)).update();
	}

	/** Tiempo hasta el primer audio de los últimos {@code last} turnos que lo tienen (012-15). Solo números. */
	public List<Integer> recentFirstAudioMs(int last) {
		return jdbc.sql("SELECT first_audio_ms FROM practice_turn WHERE first_audio_ms IS NOT NULL "
				+ "ORDER BY created_at DESC LIMIT :last").param("last", last).query(Integer.class).list();
	}

	// ------------------------------------------------------------------------------------------ retroalimentación

	/** Impide que una evaluación anterior a una corrección sobrescriba su resultado nuevo. */
	@Transactional
	public void saveFeedbackIfCurrent(TurnRow evaluated, String status, String criteriaJson, Integer total,
			String model, String promptVersion, String rubricVersion, Instant now) {
		boolean current = !jdbc.sql("""
				SELECT id FROM practice_turn WHERE id = :id AND transcript_final = :text
				  AND corrected_at IS NOT DISTINCT FROM CAST(:correctedAt AS timestamptz) FOR UPDATE""")
				.param("id", evaluated.id()).param("text", evaluated.transcriptFinal())
				.param("correctedAt", utc(evaluated.correctedAt())).query(UUID.class).list().isEmpty();
		if (current) {
			saveFeedback(evaluated.id(), status, criteriaJson, total, model, promptVersion, rubricVersion, now);
		}
	}

	public void saveFeedback(UUID turnId, String status, String criteriaJson, Integer total, String model,
			String promptVersion, String rubricVersion, Instant now) {
		jdbc.sql("""
				INSERT INTO practice_feedback (id, turn_id, status, criteria, total, model, prompt_version, rubric_version,
				    created_at)
				VALUES (:id, :turnId, :status, CAST(:criteria AS jsonb), :total, :model, :promptVersion, :rubricVersion,
				    :now)
				ON CONFLICT (turn_id) DO UPDATE SET status = EXCLUDED.status, criteria = EXCLUDED.criteria,
				    total = EXCLUDED.total, model = EXCLUDED.model, prompt_version = EXCLUDED.prompt_version,
				    rubric_version = EXCLUDED.rubric_version, created_at = EXCLUDED.created_at""")
				.param("id", UUID.randomUUID()).param("turnId", turnId).param("status", status)
				.param("criteria", criteriaJson).param("total", total).param("model", model)
				.param("promptVersion", promptVersion).param("rubricVersion", rubricVersion).param("now", utc(now))
				.update();
	}

	/** Marca la retroalimentación como pendiente solo si no lo estaba ya (un reintento a la vez). */
	public boolean markFeedbackPending(UUID turnId, String promptVersion, String rubricVersion, Instant now) {
		return jdbc.sql("""
				INSERT INTO practice_feedback (id, turn_id, status, criteria, prompt_version, rubric_version, created_at)
				VALUES (:id, :turnId, 'PENDING', '[]'::jsonb, :promptVersion, :rubricVersion, :now)
				ON CONFLICT (turn_id) DO UPDATE SET status = 'PENDING', criteria = '[]'::jsonb, total = NULL,
				    created_at = EXCLUDED.created_at
				WHERE practice_feedback.status = 'UNAVAILABLE'""")
				.param("id", UUID.randomUUID()).param("turnId", turnId).param("promptVersion", promptVersion)
				.param("rubricVersion", rubricVersion).param("now", utc(now)).update() == 1;
	}

	public List<FeedbackRow> feedbackFor(Collection<UUID> turnIds) {
		if (turnIds.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("SELECT turn_id, status, criteria::text AS criteria, total, model, prompt_version, rubric_version, "
				+ "created_at FROM practice_feedback WHERE turn_id IN (:ids)").param("ids", turnIds)
				.query(PracticeRepository::feedback).list();
	}

	// ------------------------------------------------------------------------------------------ hechos de la entrevista

	public void addEvent(UUID sessionId, String kind, String dataJson, Instant now) {
		jdbc.sql("""
				INSERT INTO interview_event (id, session_id, kind, occurred_at, data)
				VALUES (:id, :sessionId, :kind, :now, CAST(:data AS jsonb))""")
				.param("id", UUID.randomUUID()).param("sessionId", sessionId).param("kind", kind)
				.param("now", utc(now)).param("data", dataJson).update();
	}

	public List<EventRow> events(UUID sessionId) {
		return jdbc.sql("SELECT kind, occurred_at, data::text AS data FROM interview_event WHERE session_id = :sessionId "
				+ "ORDER BY occurred_at, id").param("sessionId", sessionId)
				.query((rs, n) -> new EventRow(rs.getString("kind"), instant(rs, "occurred_at"), rs.getString("data")))
				.list();
	}

	// ------------------------------------------------------------------------------------------ tiempo real (enmienda 2)

	/** Una llamada en tiempo real. Nunca guarda el SDP ni las instrucciones. */
	public record CallRow(UUID id, UUID sessionId, String providerCallId, String status, String instructionsVersion,
			String startPhase, Instant startedAt, Instant endedAt, String endReason, Integer measuredSeconds) {

		@Override
		public String toString() {
			return "CallRow[id=" + id + ", status=" + status + "]";
		}

	}

	/** Algo dicho (UTTERANCE) o una etapa aceptada (STAGE) de la conversación en tiempo real. */
	public record UtteranceRow(UUID id, UUID callId, long seq, String eventId, String kind, String speaker,
			String phase, Integer questionPosition, String text, String inputMode, Integer latencyMs,
			Instant occurredAt, Instant receivedAt) {

		@Override
		public String toString() {
			return "UtteranceRow[seq=" + seq + ", kind=" + kind + ", phase=" + phase + "]";
		}

	}

	/** Lo necesario para guardar un evento de la conversación. */
	public record NewUtterance(String eventId, String kind, String speaker, String phase, Integer questionPosition,
			String text, String inputMode, Integer latencyMs, Instant occurredAt) {

		@Override
		public String toString() {
			return "NewUtterance[kind=" + kind + ", phase=" + phase + "]";
		}

	}

	private static final String CALL_COLUMNS = """
			id, session_id, provider_call_id, status, instructions_version, start_phase, started_at, ended_at, end_reason,
			measured_seconds""";

	private static final String UTTERANCE_COLUMNS = """
			id, call_id, seq, event_id, kind, speaker, phase, question_position, text, input_mode, latency_ms, occurred_at,
			received_at""";

	private static CallRow call(ResultSet rs, int n) throws SQLException {
		return new CallRow(rs.getObject("id", UUID.class), rs.getObject("session_id", UUID.class),
				rs.getString("provider_call_id"), rs.getString("status"), rs.getString("instructions_version"),
				rs.getString("start_phase"), instant(rs, "started_at"), instant(rs, "ended_at"),
				rs.getString("end_reason"), integer(rs, "measured_seconds"));
	}

	private static UtteranceRow utterance(ResultSet rs, int n) throws SQLException {
		return new UtteranceRow(rs.getObject("id", UUID.class), rs.getObject("call_id", UUID.class), rs.getLong("seq"),
				rs.getString("event_id"), rs.getString("kind"), rs.getString("speaker"), rs.getString("phase"),
				integer(rs, "question_position"), rs.getString("text"), rs.getString("input_mode"),
				integer(rs, "latency_ms"), instant(rs, "occurred_at"), instant(rs, "received_at"));
	}

	/**
	 * Reserva la llamada ABIERTA de la sesión antes de hablar con el proveedor (así una segunda pestaña recibe 409).
	 *
	 * @throws org.springframework.dao.DuplicateKeyException si la sesión ya tiene una llamada abierta
	 */
	public void insertCall(UUID id, UUID sessionId, String instructionsVersion, String startPhase, Instant now) {
		jdbc.sql("""
				INSERT INTO realtime_call (id, session_id, status, instructions_version, start_phase, started_at)
				VALUES (:id, :sessionId, 'OPEN', :version, :phase, :now)""")
				.param("id", id).param("sessionId", sessionId).param("version", instructionsVersion)
				.param("phase", startPhase).param("now", utc(now)).update();
	}

	/** El proveedor abrió la llamada: se anota su id y el instante real de inicio. */
	public void callOpened(UUID id, String providerCallId, Instant now) {
		jdbc.sql("UPDATE realtime_call SET provider_call_id = :providerCallId, started_at = :now WHERE id = :id")
				.param("id", id).param("providerCallId", providerCallId).param("now", utc(now)).update();
	}

	/** El proveedor no abrió la llamada (012-42): queda FALLIDA y no cuenta como reconexión. */
	public void callFailed(UUID id, Instant now) {
		jdbc.sql("""
				UPDATE realtime_call SET status = 'FAILED', ended_at = :now, end_reason = 'PROVIDER_ERROR',
				    measured_seconds = 0
				WHERE id = :id AND status = 'OPEN'""").param("id", id).param("now", utc(now)).update();
	}

	/** Cierra la llamada si sigue abierta. Falso si ya estaba cerrada (otro cierre llegó antes). */
	public boolean closeCall(UUID id, String reason, Instant endedAt, int seconds) {
		return jdbc.sql("""
				UPDATE realtime_call SET status = 'CLOSED', ended_at = :endedAt, end_reason = :reason,
				    measured_seconds = :seconds
				WHERE id = :id AND status = 'OPEN'""")
				.param("id", id).param("reason", reason).param("endedAt", utc(endedAt)).param("seconds", seconds)
				.update() == 1;
	}

	public Optional<CallRow> openCall(UUID sessionId) {
		return jdbc.sql("SELECT " + CALL_COLUMNS + " FROM realtime_call WHERE session_id = :sessionId AND status = 'OPEN'")
				.param("sessionId", sessionId).query(PracticeRepository::call).optional();
	}

	public Optional<CallRow> findCall(UUID id, UUID sessionId) {
		return jdbc.sql("SELECT " + CALL_COLUMNS + " FROM realtime_call WHERE id = :id AND session_id = :sessionId")
				.param("id", id).param("sessionId", sessionId).query(PracticeRepository::call).optional();
	}

	/** Llamadas que el proveedor sí abrió (la primera y las reconexiones); las fallidas no cuentan. */
	public int countOpenedCalls(UUID sessionId) {
		return jdbc.sql("SELECT count(*) FROM realtime_call WHERE session_id = :sessionId AND status <> 'FAILED'")
				.param("sessionId", sessionId).query(Integer.class).single();
	}

	public boolean hasRealtimeCalls(UUID sessionId) {
		return countOpenedCalls(sessionId) > 0;
	}

	/** El fin fijado de las OTRAS sesiones con una llamada abierta: lo que ya está reservado del gasto (012-49). */
	public List<Instant> openCallEnds(UUID exceptSessionId) {
		return jdbc.sql("""
				SELECT s.ends_at FROM realtime_call c JOIN practice_session s ON s.id = c.session_id
				WHERE c.status = 'OPEN' AND s.ends_at IS NOT NULL AND s.id <> :sessionId""")
				.param("sessionId", exceptSessionId)
				.query((rs, n) -> instant(rs, "ends_at")).list();
	}

	/** Sesiones abiertas con una llamada abierta y su fin ya pasado antes de {@code cutoff} (012-49). */
	public List<SessionRow> realtimePastEnd(Instant cutoff) {
		return jdbc.sql("SELECT " + SESSION_COLUMNS + " FROM practice_session s WHERE status IN ('CREATED', 'IN_PROGRESS') "
				+ "AND ends_at IS NOT NULL AND ends_at < :cutoff AND EXISTS (SELECT 1 FROM realtime_call c "
				+ "WHERE c.session_id = s.id AND c.status = 'OPEN')")
				.param("cutoff", utc(cutoff)).query(PracticeRepository::session).list();
	}

	/** La primera llamada abierta con éxito: la sesión pasa a IN_PROGRESS por el canal en tiempo real (012-39). */
	public void markRealtime(UUID id, String model, String voice, String transcriptionModel, String promptVersion,
			Instant endsAt, Instant now) {
		jdbc.sql("""
				UPDATE practice_session SET status = 'IN_PROGRESS', started_at = coalesce(started_at, :now),
				    channel = 'REALTIME', realtime_model = :model, realtime_voice = :voice,
				    transcription_model = :transcriptionModel, prompt_version = :promptVersion, ends_at = :endsAt,
				    last_activity_at = :now
				WHERE id = :id AND status IN ('CREATED', 'IN_PROGRESS')""")
				.param("id", id).param("model", model).param("voice", voice)
				.param("transcriptionModel", transcriptionModel).param("promptVersion", promptVersion)
				.param("endsAt", utc(endsAt)).param("now", utc(now)).update();
	}

	/** 012-53: con FALLBACK la sesión sigue por turnos. */
	public void setChannel(UUID id, String channel) {
		jdbc.sql("UPDATE practice_session SET channel = :channel WHERE id = :id").param("id", id)
				.param("channel", channel).update();
	}

	/** Bloquea la fila de la sesión hasta el fin de la transacción: los eventos de una sesión se procesan de a uno. */
	public void lockSession(UUID id) {
		jdbc.sql("SELECT id FROM practice_session WHERE id = :id FOR UPDATE").param("id", id).query(UUID.class).list();
	}

	/** Guarda un evento; falso si ese {@code event_id} ya estaba (idempotente, 012-44). */
	public boolean insertUtterance(UUID sessionId, UUID callId, NewUtterance u, Instant now) {
		return jdbc.sql("""
				INSERT INTO practice_utterance (id, session_id, call_id, event_id, kind, speaker, phase, question_position,
				    text, input_mode, latency_ms, occurred_at, received_at)
				VALUES (:id, :sessionId, :callId, :eventId, :kind, :speaker, :phase, :position, :text, :inputMode,
				    :latencyMs, :occurredAt, :now)
				ON CONFLICT (session_id, event_id) DO NOTHING""")
				.param("id", UUID.randomUUID()).param("sessionId", sessionId).param("callId", callId)
				.param("eventId", u.eventId()).param("kind", u.kind()).param("speaker", u.speaker())
				.param("phase", u.phase()).param("position", u.questionPosition()).param("text", u.text())
				.param("inputMode", u.inputMode()).param("latencyMs", u.latencyMs())
				.param("occurredAt", utc(u.occurredAt())).param("now", utc(now)).update() == 1;
	}

	public boolean utteranceExists(UUID sessionId, String eventId) {
		return jdbc.sql("SELECT count(*) FROM practice_utterance WHERE session_id = :sessionId AND event_id = :eventId")
				.param("sessionId", sessionId).param("eventId", eventId).query(Integer.class).single() > 0;
	}

	/** Todo lo de la conversación en tiempo real, en orden de llegada. */
	public List<UtteranceRow> utterances(UUID sessionId) {
		return jdbc.sql("SELECT " + UTTERANCE_COLUMNS + " FROM practice_utterance WHERE session_id = :sessionId ORDER BY seq")
				.param("sessionId", sessionId).query(PracticeRepository::utterance).list();
	}

	/** Respuesta armada desde la conversación (012-46): una por pregunta, sin audio. */
	public void insertAssembledTurn(UUID id, UUID questionId, UUID clientTurnId, InputMode mode, String text,
			Instant now) {
		jdbc.sql("""
				INSERT INTO practice_turn (id, question_id, client_turn_id, attempt, input_mode, transcript_raw,
				    transcript_final, created_at)
				VALUES (:id, :questionId, :clientTurnId, 1, :mode, :text, :text, :now)""")
				.param("id", id).param("questionId", questionId).param("clientTurnId", clientTurnId)
				.param("mode", mode.name()).param("text", text).param("now", utc(now)).update();
	}

	/** Rearma una respuesta con intervenciones tardías, solo si la persona no la corrigió. Falso si no cambió. */
	public boolean updateAssembledTurn(UUID id, InputMode mode, String text) {
		return jdbc.sql("""
				UPDATE practice_turn SET transcript_raw = :text, transcript_final = :text, input_mode = :mode
				WHERE id = :id AND corrected_at IS NULL AND transcript_raw <> :text""")
				.param("id", id).param("mode", mode.name()).param("text", text).update() == 1;
	}

	/** Latencia del canal en tiempo real que reporta el navegador (012-55). Solo números. */
	public List<Integer> recentRealtimeLatencyMs(int last) {
		return jdbc.sql("SELECT latency_ms FROM practice_utterance WHERE latency_ms IS NOT NULL "
				+ "ORDER BY received_at DESC LIMIT :last").param("last", last).query(Integer.class).list();
	}

}
