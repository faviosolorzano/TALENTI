package com.talenti.identity.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Regla de bloqueo (RF-04, 001-10): tras {@code maxFailedAttempts} intentos fallidos consecutivos la cuenta
 * se bloquea {@code lockDuration}. Es una regla pura: no toca la base de datos y recibe la hora, así se prueba
 * con un reloj simulado.
 */
public record LoginAttemptPolicy(int maxFailedAttempts, Duration lockDuration) {

	public LoginAttemptPolicy {
		if (maxFailedAttempts < 1) {
			throw new IllegalArgumentException("maxFailedAttempts debe ser al menos 1");
		}
		if (lockDuration == null || lockDuration.isZero() || lockDuration.isNegative()) {
			throw new IllegalArgumentException("lockDuration debe ser positiva");
		}
	}

	public boolean isLocked(LockState state, Instant now) {
		return state.lockedUntil() != null && now.isBefore(state.lockedUntil());
	}

	/** Estado tras un intento fallido. Un intento durante el bloqueo no lo extiende. */
	public LockState afterFailure(LockState state, Instant now) {
		if (isLocked(state, now)) {
			return state;
		}
		// Pasado el bloqueo se empieza de cero: un solo fallo más no vuelve a bloquear.
		int previous = state.lockedUntil() != null ? 0 : state.failedCount();
		int count = previous + 1;
		Instant lockedUntil = count >= maxFailedAttempts ? now.plus(lockDuration) : null;
		return new LockState(count, lockedUntil);
	}

	/** Un ingreso correcto reinicia el contador. */
	public LockState afterSuccess() {
		return LockState.CLEAR;
	}

}
