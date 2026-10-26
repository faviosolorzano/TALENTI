package com.talenti.shared.ai;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** PostgreSQL real opcional sin Docker; solo crea y borra un esquema aleatorio propio. */
@EnabledIfSystemProperty(named = "talenti.test.jdbc-url", matches = "jdbc:postgresql:.*")
class SessionUsageCompensationJdbcTests {

	private JdbcTemplate admin, jdbc;
	private JdbcAiUsage usage;
	private String schema;
	private final UUID user = UUID.randomUUID();
	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T15:00:00Z"), ZoneOffset.UTC);

	@BeforeEach
	void setup() throws Exception {
		String url = System.getProperty("talenti.test.jdbc-url");
		String username = System.getProperty("talenti.test.jdbc-user", "review_test");
		admin = new JdbcTemplate(new DriverManagerDataSource(url, username, ""));
		schema = "test_compensation_" + UUID.randomUUID().toString().replace("-", "");
		admin.execute("CREATE SCHEMA " + schema);
		var dataSource = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema="
				+ schema + ",public", username, "");
		jdbc = new JdbcTemplate(dataSource);
		// Las migraciones reales V2–V9; V1 (vector) no interviene en estas tablas.
		for (String name : new String[] { "V2__identity_and_audit.sql", "V3__vacancy_and_skills.sql",
				"V4__require_pg_trgm.sql", "V5__student_profile.sql", "V6__profile_personal_data.sql",
				"V7__cv_reader_and_ai_usage.sql", "V8__applications.sql", "V9__practice_sessions_and_dora.sql" }) {
			jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration", name)));
		}
		jdbc.update("INSERT INTO app_user(id,email,password_hash,role) VALUES (?,?,'x','STUDENT')", user,
				user + "@ejemplo.test");
		usage = new JdbcAiUsage(JdbcClient.create(dataSource), CLOCK,
				new AiLimitsProperties(Map.of("practice-session", 1), 1, 1, ZoneId.of("America/Lima"), BigDecimal.ONE));
	}

	@AfterEach
	void cleanup() {
		if (admin != null && schema != null) {
			admin.execute("DROP SCHEMA " + schema + " CASCADE");
		}
	}

	private void migrateFix() throws Exception {
		jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V9_1__compensate_failed_session_usage.sql")));
	}

	@org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
	@org.springframework.transaction.annotation.EnableTransactionManagement
	@org.springframework.context.annotation.Import(com.talenti.practice.persistence.PracticeRepository.class)
	static class FeedbackTransactions {
	}

	@Test
	void t012_13_staleEvaluationCannotOverwriteCorrectedTranscriptFeedback() {
		UUID session = UUID.randomUUID(), question = UUID.randomUUID(), turn = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO practice_session(id,user_id,mode,basis,question_count,voice_enabled,status,attempt,
				llm_adapter,prompt_version,rubric_version,bank_version,created_at,last_activity_at)
				VALUES (?,?,'PRACTICE','PROFILE',1,false,'COMPLETED',1,'simulated','v1','v1','v1',now(),now())""", session, user);
		jdbc.update("INSERT INTO practice_question(id,session_id,position,text,source,basis_ref) VALUES (?,?,1,'Pregunta','BANK','general')",
				question, session);
		jdbc.update("""
				INSERT INTO practice_turn(id,question_id,client_turn_id,attempt,input_mode,transcript_raw,transcript_final,created_at)
				VALUES (?,?,?,1,'VOICE','Original','Original',now())""", turn, question, UUID.randomUUID());
		try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
			context.registerBean(javax.sql.DataSource.class, jdbc::getDataSource);
			context.registerBean(JdbcClient.class, () -> JdbcClient.create(jdbc.getDataSource()));
			context.registerBean(org.springframework.transaction.PlatformTransactionManager.class,
					() -> new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));
			context.register(FeedbackTransactions.class);
			context.refresh();
			var repository = context.getBean(com.talenti.practice.persistence.PracticeRepository.class);
			var original = repository.findTurn(turn, session).orElseThrow();
			repository.correctTranscript(turn, "Corregido", CLOCK.instant());
			var corrected = repository.findTurn(turn, session).orElseThrow();
			repository.saveFeedbackIfCurrent(corrected, "READY", "[]", 80, "simulated", "v1", "v1", CLOCK.instant());
			repository.saveFeedbackIfCurrent(original, "UNAVAILABLE", "[]", null, "simulated", "v1", "v1", CLOCK.instant());
			assertThat(repository.feedbackFor(java.util.List.of(turn))).singleElement().satisfies(f -> {
				assertThat(f.status()).isEqualTo("READY");
				assertThat(f.total()).isEqualTo(80);
			});
			assertThat(repository.findTurn(turn, session).orElseThrow().transcriptRaw()).isEqualTo("Original");
		}
	}

	private AiUsageEvent success(UUID session) {
		return new AiUsageEvent(session, user, AiFunction.PRACTICE_SESSION, AiLevel.BASIC, "simulated", null,
				AiUsageOutcome.SUCCESS, 0, 0, 100_000L);
	}

	private AiUsageEvent failure(UUID session) {
		return new AiUsageEvent(UUID.randomUUID(), user, AiFunction.PRACTICE_SESSION, AiLevel.BASIC, "simulated", null,
				AiUsageOutcome.FAILED_PLATFORM, 0, 0, null, session);
	}

	@Test
	void t012_29_failureRestoresPersonalAndGlobalQuotaWithoutErasingHistoryOrCost() throws Exception {
		migrateFix();
		UUID session = UUID.randomUUID();
		assertThat(usage.record(success(session))).isTrue();
		assertThat(usage.check(user, AiFunction.PRACTICE_SESSION).allowed()).isFalse();
		assertThat(usage.record(failure(session))).isTrue();
		assertThat(usage.record(failure(session))).isFalse();
		assertThat(usage.check(user, AiFunction.PRACTICE_SESSION).used()).isZero();
		assertThat(usage.check(user, AiFunction.PRACTICE_SESSION).allowed()).isTrue();
		assertThat(usage.check(UUID.randomUUID(), AiFunction.PRACTICE_SESSION).allowed()).isTrue();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_usage_event", Integer.class)).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT sum(estimated_cost_micros) FROM ai_usage_event", Long.class))
				.isEqualTo(100_000L);
	}

	@Test
	void t012_29_failureBeforeUsageAndAcrossMonthBoundaryRemainsCompensated() throws Exception {
		migrateFix();
		UUID session = UUID.randomUUID();
		usage.record(failure(session));
		usage.record(success(session));
		jdbc.update("UPDATE ai_usage_event SET occurred_at = occurred_at + interval '1 month' WHERE reverses_read_id IS NOT NULL");
		assertThat(usage.check(user, AiFunction.PRACTICE_SESSION).used()).isZero();
	}

	@Test
	void t012_29_otherUsersOrFunctionsCannotReverseThisUsage() throws Exception {
		migrateFix();
		UUID session = UUID.randomUUID();
		usage.record(success(session));
		usage.record(new AiUsageEvent(UUID.randomUUID(), null, AiFunction.INTERVIEW_SESSION, AiLevel.BASIC,
				"simulated", null, AiUsageOutcome.FAILED_PLATFORM, 0, 0, null, session));
		assertThat(usage.check(user, AiFunction.PRACTICE_SESSION).used()).isEqualTo(1);
	}

	@Test
	void t012_29_migrationRepairsPreviouslyFailedSessions() throws Exception {
		UUID session = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO practice_session(id,user_id,mode,basis,question_count,voice_enabled,status,attempt,
				llm_adapter,prompt_version,rubric_version,bank_version,created_at,last_activity_at)
				VALUES (?,?,'PRACTICE','PROFILE',1,false,'FAILED',1,'simulated','v1','v1','v1',now(),now())""", session, user);
		jdbc.update("""
				INSERT INTO ai_usage_event(id,occurred_at,user_id,function,level,adapter,outcome,read_id)
				VALUES (?,? ,?,'PRACTICE_SESSION','BASIC','simulated','SUCCESS',?)""", UUID.randomUUID(),
				java.time.OffsetDateTime.ofInstant(CLOCK.instant(), ZoneOffset.UTC), user, session);
		migrateFix();
		assertThat(usage.check(user, AiFunction.PRACTICE_SESSION).used()).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_usage_event WHERE reverses_read_id = ?", Integer.class,
				session)).isEqualTo(1);
	}
}
