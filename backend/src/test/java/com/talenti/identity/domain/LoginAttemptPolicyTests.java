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

	/** Reserva el intento y, si se comprueba la contraseña, la da por errónea (lo que hace AuthService). */
	private LockState wrongAttempt(LockState state) {
		return policy.reserve(state, clock.instant()).map(reserved -> policy.afterFailure(reserved, clock.instant()))
				.orElse(state);
	}

	private LockState failures(LockState state, int times) {
		LockState current = state;
		for (int i = 0; i < times; i++) {
			current = wrongAttempt(current);
		}
		return current;
	}

	@Test
	@DisplayName("001-10: los 4 primeros fallos solo cuentan; el quinto bloquea 15 minutos")
	void t001_10_fifthFailureLocksForFifteenMinutes() {
		LockState four = failures(LockState.CLEAR, 4);
		assertThat(four).isEqualTo(new LockState(4, null));
		assertThat(policy.isLocked(four, clock.instant())).isFalse();

		LockState five = wrongAttempt(four);

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
		assertThat(policy.reserve(locked, clock.instant())).isEmpty();

		clock.advance(Duration.ofSeconds(1));
		assertThat(policy.isLocked(locked, clock.instant())).isFalse();
		assertThat(policy.reserve(locked, clock.instant())).isPresent();
	}

	@Test
	@DisplayName("001-10: un intento durante el bloqueo no se comprueba y no lo extiende")
	void t001_10_attemptDuringLockDoesNotExtendIt() {
		LockState locked = failures(LockState.CLEAR, 5);
		clock.advance(Duration.ofMinutes(10));

		assertThat(policy.reserve(locked, clock.instant())).isEmpty();
		assertThat(wrongAttempt(locked)).isEqualTo(locked);
		assertThat(policy.afterFailure(locked, clock.instant())).isEqualTo(locked);
	}

	@Test
	@DisplayName("001-10: pasado el bloqueo se empieza de cero: un fallo cuenta como el primero")
	void t001_10_afterLockExpiresCountingStartsOver() {
		LockState locked = failures(LockState.CLEAR, 5);
		clock.advance(Duration.ofMinutes(16));

		LockState next = wrongAttempt(locked);

		assertThat(next).isEqualTo(new LockState(1, null));
		assertThat(policy.isLocked(next, clock.instant())).isFalse();
	}

	@Test
	@DisplayName("001-10: nunca se reservan más de 5 comprobaciones antes del bloqueo (solicitudes simultáneas)")
	void t001_10_reservationsNeverExceedTheMaximum() {
		// Diez solicitudes reservan antes de que cualquiera termine de comparar.
		LockState state = LockState.CLEAR;
		int allowed = 0;
		for (int i = 0; i < 10; i++) {
			var reserved = policy.reserve(state, clock.instant());
			if (reserved.isPresent()) {
				allowed++;
				state = reserved.get();
			}
		}
		assertThat(allowed).isEqualTo(5);
		assertThat(state).isEqualTo(new LockState(5, null));
		// Los fallos que terminan después fijan el bloqueo una sola vez.
		LockState locked = policy.afterFailure(state, clock.instant());
		assertThat(locked.lockedUntil()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
		assertThat(policy.afterFailure(locked, clock.instant().plusSeconds(30))).isEqualTo(locked);
	}

	@Test
	@DisplayName("001-10: un ingreso correcto reinicia el contador, pero nunca borra un bloqueo vigente")
	void t001_10_successResetsTheCounterButNeverAVigentLock() {
		LockState afterSuccess = policy.afterSuccess(failures(LockState.CLEAR, 4), clock.instant());
		assertThat(afterSuccess).isEqualTo(LockState.CLEAR);
		// Cuatro fallos, éxito, cuatro fallos: nunca llega a 5 seguidos.
		assertThat(policy.isLocked(failures(afterSuccess, 4), clock.instant())).isFalse();

		// La cuenta quedó bloqueada mientras una solicitud con la contraseña correcta terminaba: el bloqueo sigue.
		LockState locked = failures(LockState.CLEAR, 5);
		assertThat(policy.afterSuccess(locked, clock.instant())).isEqualTo(locked);
		// Un bloqueo vencido sí se limpia.
		clock.advance(Duration.ofMinutes(16));
		assertThat(policy.afterSuccess(locked, clock.instant())).isEqualTo(LockState.CLEAR);
	}

	@Test
	@DisplayName("001-10: los umbrales son configurables")
	void t001_10_thresholdsAreConfigurable() {
		LoginAttemptPolicy strict = new LoginAttemptPolicy(2, Duration.ofMinutes(1));
		LockState state = LockState.CLEAR;
		for (int i = 0; i < 2; i++) {
			state = strict.afterFailure(strict.reserve(state, clock.instant()).orElseThrow(), clock.instant());
		}
		assertThat(state.lockedUntil()).isEqualTo(clock.instant().plusSeconds(60));
		assertThatThrownBy(() -> new LoginAttemptPolicy(0, Duration.ofMinutes(1)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new LoginAttemptPolicy(5, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
	}

}
