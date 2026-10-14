package com.talenti.shared.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.talenti.support.EmptyDatabaseConfiguration;
import com.talenti.support.MutableClock;
import com.talenti.support.TestClockConfiguration;

/**
 * Cupos de IA (009-28, 009-29, 009-31, 009-34): mes calendario en America/Lima con reloj simulado, fallas que no
 * cuentan, bloqueos que sí se anotan, topes personal y global y el orden de comprobación. Corre sobre una base de datos
 * APARTE para que los conteos globales sean exactos; cada prueba usa su propio mes para no mezclarse con las demás.
 */
@SpringBootTest(properties = { "talenti.ai.limits.global-daily=4", "talenti.ai.limits.global-monthly=6",
		"talenti.ai.limits.per-user-monthly.practice-session=2" })
@Import({ EmptyDatabaseConfiguration.class, TestClockConfiguration.class })
class AiUsageQuotaTests {

	private static final AtomicInteger TEST_NUMBER = new AtomicInteger();

	@Autowired
	private AiUsageQuota quota;

	@Autowired
	private AiUsageRecorder recorder;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbc;

	/** Cada prueba vive en un mes distinto desde 2031 (el 15 a mediodía en Lima): los conteos globales no se cruzan. */
	@BeforeEach
	void ownMonth() {
		int n = TEST_NUMBER.incrementAndGet();
		clock.set(ZonedDateTime.of(2031, 1, 15, 12, 0, 0, 0, ZoneId.of("America/Lima")).plusMonths(n).toInstant());
	}

	private UUID newUser() {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO app_user (id, email, password_hash, role) VALUES (?, ?, 'x', 'STUDENT')", id,
				"cupo." + id + "@ejemplo.test");
		return id;
	}

	private boolean record(UUID user, AiFunction function, AiUsageOutcome outcome) {
		return recorder.record(new AiUsageEvent(UUID.randomUUID(), user, function, AiLevel.ENHANCED, "simulated-ai",
				"modelo-simulado", outcome, 100, 50));
	}

	private void successes(UUID user, int count) {
		for (int i = 0; i < count; i++) {
			record(user, AiFunction.CV_READ, AiUsageOutcome.SUCCESS);
		}
	}

	@Test
	@DisplayName("009-28: tres lecturas completadas agotan el cupo personal del mes; la cuarta persona-mes se bloquea")
	void t009_28_personalMonthlyQuota() {
		UUID user = newUser();

		assertThat(quota.check(user, AiFunction.CV_READ).allowed()).isTrue();
		successes(user, 2);
		QuotaStatus two = quota.check(user, AiFunction.CV_READ);
		assertThat(two.allowed()).isTrue();
		assertThat(two.used()).isEqualTo(2);
		assertThat(two.limit()).isEqualTo(3);
		successes(user, 1);

		QuotaStatus three = quota.check(user, AiFunction.CV_READ);
		assertThat(three.blockedBy()).isEqualTo(QuotaStatus.Block.PERSONAL);
		assertThat(three.used()).isEqualTo(3);
		assertThat(quota.check(newUser(), AiFunction.CV_READ).allowed()).as("otra persona no se ve afectada").isTrue();
	}

	@Test
	@DisplayName("009-28: el mes calendario es el de Lima: a las 23:30 del 31 en Lima sigue siendo el mes viejo, y a las 00:30 del 1 se renueva")
	void t009_28_calendarMonthInLima() {
		clock.set(Instant.parse("2040-11-01T04:30:00Z"));
		UUID user = newUser();
		successes(user, 3);

		QuotaStatus blocked = quota.check(user, AiFunction.CV_READ);
		assertThat(blocked.blockedBy()).as("en UTC ya es noviembre, en Lima todavía es el 31 de octubre")
				.isEqualTo(QuotaStatus.Block.PERSONAL);
		assertThat(blocked.personalResets()).isEqualTo(Instant.parse("2040-11-01T05:00:00Z"));

		clock.advance(Duration.ofHours(1));

		QuotaStatus renewed = quota.check(user, AiFunction.CV_READ);
		assertThat(renewed.allowed()).isTrue();
		assertThat(renewed.used()).isZero();
		assertThat(renewed.personalResets()).isEqualTo(Instant.parse("2040-12-01T05:00:00Z"));
	}

	@Test
	@DisplayName("009-28: las fallas de la plataforma y los bloqueos se anotan pero no cuentan en el cupo")
	void t009_28_failuresAndBlocksDoNotCount() {
		UUID user = newUser();
		for (int i = 0; i < 5; i++) {
			record(user, AiFunction.CV_READ, AiUsageOutcome.FAILED_PLATFORM);
			record(user, AiFunction.CV_READ, AiUsageOutcome.BLOCKED_PERSONAL_QUOTA);
			record(user, AiFunction.CV_READ, AiUsageOutcome.BLOCKED_GLOBAL_QUOTA);
		}

		QuotaStatus status = quota.check(user, AiFunction.CV_READ);

		assertThat(status.allowed()).isTrue();
		assertThat(status.used()).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_usage_event WHERE user_id = ?", Integer.class, user))
				.as("todos los intentos quedaron anotados").isEqualTo(15);
	}

	@Test
	@DisplayName("009-29: el tope global por día agota la lectura mejorada para todos; al día siguiente (Lima) vuelve")
	void t009_29_globalDailyLimit() {
		for (int i = 0; i < 4; i++) {
			successes(newUser(), 1);
		}
		UUID someone = newUser();

		QuotaStatus blocked = quota.check(someone, AiFunction.CV_READ);
		assertThat(blocked.blockedBy()).isEqualTo(QuotaStatus.Block.GLOBAL);

		clock.advance(Duration.ofDays(1));
		assertThat(quota.check(someone, AiFunction.CV_READ).allowed()).isTrue();
	}

	@Test
	@DisplayName("009-29: el tope global por mes también agota; los usos de cuentas ya eliminadas siguen contando")
	void t009_29_globalMonthlyLimit() {
		UUID leaver = newUser();
		successes(leaver, 3);
		jdbc.update("DELETE FROM app_user WHERE id = ?", leaver);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_usage_event WHERE user_id IS NULL AND outcome = 'SUCCESS'",
				Integer.class)).as("la medición sobrevive anonimizada (RN-12)").isGreaterThanOrEqualTo(3);
		for (int day = 0; day < 3; day++) {
			successes(newUser(), 1);
			clock.advance(Duration.ofDays(1));
		}
		UUID someone = newUser();

		QuotaStatus status = quota.check(someone, AiFunction.CV_READ);

		assertThat(status.blockedBy()).as("6 usos en el mes = el tope mensual").isEqualTo(QuotaStatus.Block.GLOBAL);
	}

	@Test
	@DisplayName("009-29: el orden de comprobación es primero el cupo personal y luego el global")
	void t009_29_personalIsCheckedFirst() {
		UUID user = newUser();
		successes(user, 3);
		successes(newUser(), 1);

		assertThat(quota.check(user, AiFunction.CV_READ).blockedBy()).isEqualTo(QuotaStatus.Block.PERSONAL);
	}

	@Test
	@DisplayName("009-31: una lectura se anota una sola vez (idempotente por read_id)")
	void t009_31_recordIsIdempotent() {
		UUID user = newUser();
		UUID readId = UUID.randomUUID();
		AiUsageEvent event = new AiUsageEvent(readId, user, AiFunction.CV_READ, AiLevel.ENHANCED, "simulated-ai",
				"modelo-simulado", AiUsageOutcome.SUCCESS, 10, 5);

		assertThat(recorder.exists(readId)).isFalse();
		assertThat(recorder.record(event)).isTrue();
		assertThat(recorder.record(event)).isFalse();

		assertThat(recorder.exists(readId)).isTrue();
		assertThat(quota.check(user, AiFunction.CV_READ).used()).isEqualTo(1);
	}

	@Test
	@DisplayName("009-31: la suma del mes por persona coincide con el cupo aplicado")
	void t009_31_monthlySumEqualsQuota() {
		UUID user = newUser();
		successes(user, 2);
		record(user, AiFunction.CV_READ, AiUsageOutcome.FAILED_PLATFORM);

		Integer sum = jdbc.queryForObject("""
				SELECT count(*) FROM ai_usage_event
				WHERE user_id = ? AND function = 'CV_READ' AND outcome = 'SUCCESS'""", Integer.class, user);

		assertThat(quota.check(user, AiFunction.CV_READ).used()).isEqualTo(sum).isEqualTo(2);
	}

	@Test
	@DisplayName("009-34: la práctica con DORA usa la misma tabla y la misma configuración: sesiones por mes, sin cambiar la forma")
	void t009_34_practiceSessionsReuseTheMechanism() {
		UUID user = newUser();
		successes(user, 1);
		record(user, AiFunction.PRACTICE_SESSION, AiUsageOutcome.SUCCESS);
		assertThat(quota.check(user, AiFunction.PRACTICE_SESSION).allowed()).isTrue();
		record(user, AiFunction.PRACTICE_SESSION, AiUsageOutcome.SUCCESS);

		QuotaStatus practice = quota.check(user, AiFunction.PRACTICE_SESSION);

		assertThat(practice.blockedBy()).isEqualTo(QuotaStatus.Block.PERSONAL);
		assertThat(practice.limit()).isEqualTo(2);
		assertThat(quota.check(user, AiFunction.CV_READ).used()).as("la lectura de CV cuenta aparte").isEqualTo(1);
		assertThat(quota.check(user, AiFunction.STT).limit()).as("sin tope personal configurado").isEqualTo(-1);
		assertThat(quota.check(user, AiFunction.STT).allowed()).isTrue();
	}

}
