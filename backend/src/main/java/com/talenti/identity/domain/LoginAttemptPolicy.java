package com.talenti.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Regla de bloqueo (RF-04, 001-10): tras {@code maxFailedAttempts} intentos fallidos consecutivos la cuenta
 * se bloquea {@code lockDuration}. Es una regla pura: no toca la base de datos y recibe la hora, así se prueba
 * con un reloj simulado.
 *
 * <p>El intento se <strong>reserva antes de comparar la contraseña</strong> ({@link #reserve}): cada intento suma 1
 * al contador bajo bloqueo de fila, y cuando ya hay {@code maxFailedAttempts} intentos reservados no se
 * permiten más comprobaciones. Así, solicitudes simultáneas no pueden comparar más de {@code maxFailedAttempts}
 * contraseñas antes del bloqueo. El bloqueo en sí lo fija el fallo que completa el máximo ({@link #afterFailure}).
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

	/**
	 * Reserva un intento antes de comparar la contraseña. Vacío si no se puede comprobar: la cuenta está
	 * bloqueada, o ya hay {@code maxFailedAttempts} intentos reservados (con el bloqueo por fijarse). Pasado un
	 * bloqueo se empieza de cero.
	 */
	public Optional<LockState> reserve(LockState state, Instant now) {
		if (isLocked(state, now)) {
			return Optional.empty();
		}
		LockState base = state.lockedUntil() != null ? LockState.CLEAR : state;
		if (base.failedCount() >= maxFailedAttempts) {
			return Optional.empty();
		}
		return Optional.of(new LockState(base.failedCount() + 1, null));
	}

	/** Estado tras comprobar una contraseña errónea: si ya se completó el máximo, se fija el bloqueo. */
	public LockState afterFailure(LockState state, Instant now) {
		if (state.lockedUntil() != null || state.failedCount() < maxFailedAttempts) {
			// Ya bloqueada (un fallo no extiende el bloqueo) o todavía quedan intentos.
			return state;
		}
		return new LockState(state.failedCount(), now.plus(lockDuration));
	}

	/**
	 * Estado tras un ingreso correcto: reinicia el contador, salvo que la cuenta haya quedado bloqueada mientras
	 * tanto (por solicitudes simultáneas): un bloqueo vigente nunca se borra.
	 */
	public LockState afterSuccess(LockState state, Instant now) {
		return isLocked(state, now) ? state : LockState.CLEAR;
	}

}
