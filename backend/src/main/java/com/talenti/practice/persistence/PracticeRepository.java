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

import com.talenti.practice.domain.Basis;
import com.talenti.practice.domain.InputMode;
import com.talenti.practice.domain.QuestionSource;
import com.talenti.practice.domain.SessionMode;
import com.talenti.practice.domain.SessionStatus;

/**
 * Único lugar del módulo que toca sus tablas: {@code practice_session}, {@code practice_question},
 * {@code practice_turn}, {@code practice_feedback} e {@code interview_event} (principio 12). Nunca guarda audio. Las
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
			Instant lastActivityAt) {

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
			tts_voice, prompt_version, rubric_version, bank_version, created_at, started_at, ended_at, last_activity_at""";

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
				instant(rs, "last_activity_at"));
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

}
