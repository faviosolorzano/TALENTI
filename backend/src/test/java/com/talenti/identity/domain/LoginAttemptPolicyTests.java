package com.talenti.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.support.MutableClock;

/** 001-10: regla de bloqueo con un reloj simulado. */
class LoginAttemptPolicyTests {

	private final LoginAttemptPolicy policy = new LoginAttemptPolicy(5, Duration.ofMinutes(15));

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-11T12:00:00Z"));

	private LockState failures(LockState state, int times) {
		LockState current = state;
		for (int i = 0; i < times; i++) {
			current = policy.afterFailure(current, clock.instant());
		}
		return current;
	}

	@Test
	@DisplayName("001-10: los 4 primeros fallos solo cuentan; el quinto bloquea 15 minutos")
	void t001_10_fifthFailureLocksForFifteenMinutes() {
		LockState four = failures(LockState.CLEAR, 4);
		assertThat(four).isEqualTo(new LockState(4, null));
		assertThat(policy.isLocked(four, clock.instant())).isFalse();

		LockState five = policy.afterFailure(four, clock.instant());

		assertThat(five.failedCount()).isEqualTo(5);
		assertThat(five.lockedUntil()).isEqualTo(Instant.parse("2026-10-11T12:15:00Z"));
		assertThat(policy.isLocked(five, clock.instant())).isTrue();
	}

	@Test
	@DisplayName("001-10: la cuenta sigue bloqueada hasta el último segundo y se libera a los 15 minutos")
	void t001_10_lockExpiresAfterFifteenMinutes() {
		LockState locked = failures(LockState.CLEAR, 5);

		clock.advance(Duration.ofMinutes(14).plusSeconds(59));
		assertThat(policy.isLocked(locked, clock.instant())).isTrue();

		clock.advance(Duration.ofSeconds(1));
		assertThat(policy.isLocked(locked, clock.instant())).isFalse();
	}

	@Test
	@DisplayName("001-10: un intento durante el bloqueo no lo extiende")
	void t001_10_attemptDuringLockDoesNotExtendIt() {
		LockState locked = failures(LockState.CLEAR, 5);
		clock.advance(Duration.ofMinutes(10));

		assertThat(policy.afterFailure(locked, clock.instant())).isEqualTo(locked);
	}

	@Test
	@DisplayName("001-10: pasado el bloqueo se empieza de cero: un fallo cuenta como el primero")
	void t001_10_afterLockExpiresCountingStartsOver() {
		LockState locked = failures(LockState.CLEAR, 5);
		clock.advance(Duration.ofMinutes(16));

		LockState next = policy.afterFailure(locked, clock.instant());

		assertThat(next).isEqualTo(new LockState(1, null));
		assertThat(policy.isLocked(next, clock.instant())).isFalse();
	}

	@Test
	@DisplayName("001-10: un ingreso correcto reinicia el contador")
	void t001_10_successResetsTheCounter() {
		assertThat(policy.afterSuccess()).isEqualTo(LockState.CLEAR);
		// Cuatro fallos, éxito, cuatro fallos: nunca llega a 5 seguidos.
		failures(LockState.CLEAR, 4);
		LockState afterSuccess = policy.afterSuccess();
		LockState state = failures(afterSuccess, 4);
		assertThat(policy.isLocked(state, clock.instant())).isFalse();
	}

	@Test
	@DisplayName("001-10: los umbrales son configurables")
	void t001_10_thresholdsAreConfigurable() {
		LoginAttemptPolicy strict = new LoginAttemptPolicy(2, Duration.ofMinutes(1));
		LockState state = strict.afterFailure(strict.afterFailure(LockState.CLEAR, clock.instant()), clock.instant());
		assertThat(state.lockedUntil()).isEqualTo(clock.instant().plusSeconds(60));
		assertThatThrownBy(() -> new LoginAttemptPolicy(0, Duration.ofMinutes(1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoginAttemptPolicy(5, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
	}

}
