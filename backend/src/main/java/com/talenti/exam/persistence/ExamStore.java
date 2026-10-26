package com.talenti.exam.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import com.talenti.exam.domain.*;
import tools.jackson.databind.json.JsonMapper;

/** Único acceso a las tablas exam; el servicio delimita las transacciones y los bloqueos de intento. */
@Repository
public class ExamStore {
	private final JdbcClient jdbc;
	private final JsonMapper json;
	public ExamStore(JdbcClient jdbc, JsonMapper json) { this.jdbc = jdbc; this.json = json; }
	public record SetRow(UUID id, UUID vacancyId, int revision, String status, Blueprint blueprint, String source,
			String model, String promptVersion, String verifierVersion, String bankVersion, String failureReason, Instant createdAt, Instant readyAt) { }
	public record VersionRow(UUID id, UUID setId, String label, String verificationJson, boolean locked) { }
	public record AttemptRow(UUID id, UUID applicationId, UUID userId, UUID versionId, long shuffleSeed,
			AttemptStatus status, String gradingStatus, String noticeVersion, Instant noticeAcceptedAt,
			Instant startedAt, Instant deadlineAt, Instant endedAt, String closedReason, int screenLeftCount,
			BigDecimal score100, BigDecimal score20, String scoringVersion, Instant createdAt) { }
	public record AnswerRow(UUID id, UUID attemptId, UUID questionId, String choiceId, String text, String code,
			long revision, int runCount, String gradeStatus, BigDecimal points, String gradeDetail,
			int gradingAttempts, Instant nextRetryAt, Instant updatedAt) {
		@Override public String toString() { return "ExamAnswer[id="+id+", revision="+revision+"]"; }
	}
	public record EventRow(UUID id, String kind, UUID clientEventId, Instant clientOccurredAt, Instant occurredAt, String dataJson) { }
	private static OffsetDateTime utc(Instant instant) { return instant == null ? null : OffsetDateTime.ofInstant(instant,ZoneOffset.UTC); }
	private static Instant instant(ResultSet rs,String column) throws SQLException { var value=rs.getObject(column,OffsetDateTime.class); return value == null ? null : value.toInstant(); }
	public String json(Object value) { return json.writeValueAsString(value); }
	private SetRow set(ResultSet r,int n) throws SQLException {
		return new SetRow(r.getObject("id",UUID.class),r.getObject("vacancy_id",UUID.class),r.getInt("revision"),r.getString("status"),
				json.readValue(r.getString("blueprint"),Blueprint.class),r.getString("source"),r.getString("model"),r.getString("prompt_version"),
				r.getString("verifier_version"),r.getString("bank_version"),r.getString("failure_reason"),instant(r,"created_at"),instant(r,"ready_at"));
	}
	private static AttemptRow attempt(ResultSet r,int n) throws SQLException {
		return new AttemptRow(r.getObject("id",UUID.class),r.getObject("application_id",UUID.class),r.getObject("user_id",UUID.class),
				r.getObject("version_id",UUID.class),r.getLong("shuffle_seed"),AttemptStatus.valueOf(r.getString("status")),r.getString("grading_status"),
				r.getString("notice_version"),instant(r,"notice_accepted_at"),instant(r,"started_at"),instant(r,"deadline_at"),instant(r,"ended_at"),
				r.getString("closed_reason"),r.getInt("screen_left_count"),r.getBigDecimal("score_100"),r.getBigDecimal("score_20"),
				r.getString("scoring_version"),instant(r,"created_at"));
	}
	private static AnswerRow answer(ResultSet r,int n) throws SQLException {
		return new AnswerRow(r.getObject("id",UUID.class),r.getObject("attempt_id",UUID.class),r.getObject("question_id",UUID.class),
				r.getString("choice_id"),r.getString("text"),r.getString("code"),r.getLong("revision"),r.getInt("run_count"),r.getString("grade_status"),
				r.getBigDecimal("points"),r.getString("grade_detail"),r.getInt("grading_attempts"),instant(r,"next_retry_at"),instant(r,"updated_at"));
	}
	public Optional<SetRow> activeSet(UUID vacancy) {
		return jdbc.sql("SELECT * FROM exam_set WHERE vacancy_id=:id AND status NOT IN ('FAILED','SUPERSEDED')")
				.param("id",vacancy).query(this::set).optional();
	}
	public Optional<SetRow> latestSet(UUID vacancy) {
		return jdbc.sql("SELECT * FROM exam_set WHERE vacancy_id=:id ORDER BY revision DESC LIMIT 1")
				.param("id",vacancy).query(this::set).optional();
	}
	public Optional<SetRow> findSet(UUID id) { return jdbc.sql("SELECT * FROM exam_set WHERE id=:id").param("id",id).query(this::set).optional(); }
	public Optional<UUID> insertSet(UUID vacancy, Blueprint blueprint, Instant now) {
		return jdbc.sql("""
				INSERT INTO exam_set(id,vacancy_id,revision,status,blueprint,created_at)
				SELECT :id,:vacancyId,coalesce(max(revision),0)+1,'GENERATING',CAST(:blueprint AS jsonb),:now
				FROM exam_set WHERE vacancy_id=:vacancyId ON CONFLICT DO NOTHING RETURNING id""")
				.param("id",UUID.randomUUID()).param("vacancyId",vacancy).param("blueprint",json(blueprint)).param("now",utc(now)).query(UUID.class).optional();
	}
	public void setStatus(UUID id,String status,String reason) {
		jdbc.sql("UPDATE exam_set SET status=:status,failure_reason=:reason WHERE id=:id AND status IN ('GENERATING','VERIFYING')")
				.param("id",id).param("status",status).param("reason",reason).update();
	}
	public void ready(UUID id,String source,String model,String promptVersion,String bankVersion,Instant now) {
		jdbc.sql("""
				UPDATE exam_set SET status='READY',source=:source,model=:model,prompt_version=:prompt,
				verifier_version=:verifier,bank_version=:bank,ready_at=:now,failure_reason=NULL
				WHERE id=:id AND status='VERIFYING'""")
				.param("id",id).param("source",source).param("model",model).param("prompt",promptVersion)
				.param("verifier",ExamVerifier.VERSION).param("bank",bankVersion).param("now",utc(now)).update();
	}
	public List<SetRow> unfinishedSets() { return jdbc.sql("SELECT * FROM exam_set WHERE status IN ('GENERATING','VERIFYING')").query(this::set).list(); }
	public void insertVersion(UUID id,UUID setId,String label,List<ExamQuestion> questions,ExamVerifier.Report report) {
		jdbc.sql("INSERT INTO exam_version(id,set_id,label,verification_report) VALUES (:id,:set,:label,CAST(:report AS jsonb))")
				.param("id",id).param("set",setId).param("label",label).param("report",json(report)).update();
		for(var q:questions) {
			jdbc.sql("""
					INSERT INTO exam_question(id,version_id,slot,type,skill_slug,difficulty,points,prompt,content,source)
					VALUES (:id,:version,:slot,:type,:skillSlug,:difficulty,:points,:prompt,CAST(:content AS jsonb),:source)""")
					.param("id",q.id()).param("version",id).param("slot",q.slot()).param("type",q.type().name())
					.param("skillSlug",q.skillSlug()).param("difficulty",q.difficulty()).param("points",q.points()).param("prompt",q.prompt())
					.param("content",json(q)).param("source",q.source().name()).update();
		}
	}
	public List<VersionRow> versions(UUID setId) {
		return jdbc.sql("SELECT * FROM exam_version WHERE set_id=:set ORDER BY label").param("set",setId)
				.query((r,n)->new VersionRow(r.getObject("id",UUID.class),r.getObject("set_id",UUID.class),r.getString("label"),r.getString("verification_report"),r.getBoolean("locked"))).list();
	}
	public List<ExamQuestion> questions(UUID versionId) {
		return jdbc.sql("SELECT content FROM exam_question WHERE version_id=:version ORDER BY slot").param("version",versionId)
				.query((r,n)->json.readValue(r.getString("content"),ExamQuestion.class)).list();
	}
	public AttemptRow insertAttempt(UUID application,UUID user,UUID version,long seed,String notice,Instant now) {
		UUID id=UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO exam_attempt(id,application_id,user_id,version_id,shuffle_seed,status,notice_version,
				notice_accepted_at,scoring_version,created_at) VALUES (:id,:application,:user,:version,:seed,'NOT_STARTED',:notice,:now,:scoring,:now)""")
				.param("id",id).param("application",application).param("user",user).param("version",version).param("seed",seed)
				.param("notice",notice).param("now",utc(now)).param("scoring",ExamScoring.VERSION).update();
		jdbc.sql("UPDATE exam_version SET locked=true WHERE id=:id").param("id",version).update();
		for(var q:questions(version)) jdbc.sql("""
				INSERT INTO exam_answer(id,attempt_id,question_id,updated_at) VALUES (:id,:attempt,:question,:now)""")
				.param("id",UUID.randomUUID()).param("attempt",id).param("question",q.id()).param("now",utc(now)).update();
		return findAttempt(id).orElseThrow();
	}
	public Optional<AttemptRow> findAttempt(UUID id) { return jdbc.sql("SELECT * FROM exam_attempt WHERE id=:id").param("id",id).query(ExamStore::attempt).optional(); }
	public Optional<AttemptRow> ownedAttempt(UUID id,UUID user,boolean lock) {
		return jdbc.sql("SELECT * FROM exam_attempt WHERE id=:id AND user_id=:user"+(lock?" FOR UPDATE":""))
				.param("id",id).param("user",user).query(ExamStore::attempt).optional();
	}
	public Optional<AttemptRow> lockAttempt(UUID id) {
		return jdbc.sql("SELECT * FROM exam_attempt WHERE id=:id FOR UPDATE").param("id",id).query(ExamStore::attempt).optional();
	}
	public Optional<AttemptRow> forApplication(UUID application,UUID user) {
		return jdbc.sql("SELECT * FROM exam_attempt WHERE application_id=:application AND user_id=:user")
				.param("application",application).param("user",user).query(ExamStore::attempt).optional();
	}
	public Optional<AttemptRow> forApplication(UUID application) {
		return jdbc.sql("SELECT * FROM exam_attempt WHERE application_id=:application").param("application",application).query(ExamStore::attempt).optional();
	}
	public void start(UUID id,Instant now,Instant deadline) {
		jdbc.sql("UPDATE exam_attempt SET status='IN_PROGRESS',started_at=:now,deadline_at=:deadline WHERE id=:id AND status='NOT_STARTED'")
				.param("id",id).param("now",utc(now)).param("deadline",utc(deadline)).update();
	}
	public boolean close(UUID id,AttemptStatus status,String reason,Instant now) {
		return jdbc.sql("""
				UPDATE exam_attempt SET status=:status,closed_reason=:reason,ended_at=:now,
				grading_status=CASE WHEN :status='CLOSED_WITHDRAWN' THEN 'NOT_STARTED' ELSE 'IN_PROGRESS' END
				WHERE id=:id AND status IN ('NOT_STARTED','IN_PROGRESS')""")
				.param("id",id).param("status",status.name()).param("reason",reason).param("now",utc(now)).update()==1;
	}
	public List<AttemptRow> expired(Instant now) {
		return jdbc.sql("SELECT * FROM exam_attempt WHERE status='IN_PROGRESS' AND deadline_at<=:now")
				.param("now",utc(now)).query(ExamStore::attempt).list();
	}
	public List<AttemptRow> needsGrading(Instant now) {
		return jdbc.sql("""
				SELECT DISTINCT a.* FROM exam_attempt a JOIN exam_answer r ON r.attempt_id=a.id
				WHERE a.status IN ('SUBMITTED','TIMED_OUT','CLOSED_BY_RULE') AND r.grade_status='PENDING'
				AND (r.next_retry_at IS NULL OR r.next_retry_at<=:now)""").param("now",utc(now)).query(ExamStore::attempt).list();
	}
	public List<AnswerRow> answers(UUID attempt) {
		return jdbc.sql("SELECT * FROM exam_answer WHERE attempt_id=:id ORDER BY question_id").param("id",attempt).query(ExamStore::answer).list();
	}
	public Optional<AnswerRow> answer(UUID attempt,UUID question) {
		return jdbc.sql("SELECT * FROM exam_answer WHERE attempt_id=:attempt AND question_id=:question")
				.param("attempt",attempt).param("question",question).query(ExamStore::answer).optional();
	}
	public boolean saveAnswer(UUID attempt,UUID question,long revision,String choice,String text,String code,Instant now) {
		return jdbc.sql("""
				UPDATE exam_answer SET choice_id=:choice,text=:text,code=:code,revision=revision+1,updated_at=:now
				WHERE attempt_id=:attempt AND question_id=:question AND revision=:revision""")
				.param("attempt",attempt).param("question",question).param("revision",revision).param("choice",choice)
				.param("text",text).param("code",code).param("now",utc(now)).update()==1;
	}
	public boolean consumeRun(UUID answer,int maximum) {
		return jdbc.sql("UPDATE exam_answer SET run_count=run_count+1 WHERE id=:id AND run_count<:max")
				.param("id",answer).param("max",maximum).update()==1;
	}
	public void refundRun(UUID answer) { jdbc.sql("UPDATE exam_answer SET run_count=greatest(0,run_count-1) WHERE id=:id").param("id",answer).update(); }
	public boolean event(UUID attempt,String kind,UUID clientEvent,Instant clientAt,Instant now,Map<String,Object> data) {
		return jdbc.sql("""
				INSERT INTO exam_event(id,attempt_id,kind,client_event_id,client_occurred_at,occurred_at,data)
				VALUES (:id,:attempt,:kind,:client,:clientAt,:now,CAST(:data AS jsonb)) ON CONFLICT DO NOTHING""")
				.param("id",UUID.randomUUID()).param("attempt",attempt).param("kind",kind).param("client",clientEvent)
				.param("clientAt",utc(clientAt)).param("now",utc(now)).param("data",json(data)).update()==1;
	}
	public List<EventRow> events(UUID attempt) {
		return jdbc.sql("SELECT * FROM exam_event WHERE attempt_id=:attempt ORDER BY occurred_at,id").param("attempt",attempt)
				.query((r,n)->new EventRow(r.getObject("id",UUID.class),r.getString("kind"),r.getObject("client_event_id",UUID.class),
						instant(r,"client_occurred_at"),instant(r,"occurred_at"),r.getString("data"))).list();
	}
	public void incrementScreenLeft(UUID attempt) { jdbc.sql("UPDATE exam_attempt SET screen_left_count=screen_left_count+1 WHERE id=:id").param("id",attempt).update(); }
	public boolean claimGrade(UUID answer,Instant now,Instant leaseUntil) {
		return jdbc.sql("""
				UPDATE exam_answer SET grading_attempts=grading_attempts+1,next_retry_at=:lease
				WHERE id=:id AND grade_status='PENDING' AND (next_retry_at IS NULL OR next_retry_at<=:now)""")
				.param("id",answer).param("now",utc(now)).param("lease",utc(leaseUntil)).update()==1;
	}
	public void grade(UUID answer,String status,BigDecimal points,Object detail,Instant retry) {
		jdbc.sql("UPDATE exam_answer SET grade_status=:status,points=:points,grade_detail=CAST(:detail AS jsonb),next_retry_at=:retry WHERE id=:id")
				.param("id",answer).param("status",status).param("points",points).param("detail",json(detail)).param("retry",utc(retry)).update();
	}
	public void total(UUID attempt,String status,BigDecimal score100,BigDecimal score20) {
		jdbc.sql("UPDATE exam_attempt SET grading_status=:status,score_100=:score100,score_20=:score20 WHERE id=:id")
				.param("id",attempt).param("status",status).param("score100",score100).param("score20",score20).update();
	}
}
