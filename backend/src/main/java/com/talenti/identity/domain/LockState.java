package com.talenti.identity.domain;

import java.time.Instant;

/** Contador de intentos fallidos y fin del bloqueo de una cuenta. */
public record LockState(int failedCount, Instant lockedUntil) {

	public static final LockState CLEAR = new LockState(0, null);

}
