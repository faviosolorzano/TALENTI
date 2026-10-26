package com.talenti.exam.domain;

import java.time.Instant;

/** Hechos y reglas del intento, sin calificar fraude ni inferir rasgos. */
public final class AttemptRules {
	private AttemptRules() { }
	public enum EventKind { SCREEN_LEFT, SCREEN_RETURNED, FOCUS_LOST, COPY_ATTEMPT, PASTE_ATTEMPT,
		GAZE_AWAY, CONNECTION_LOST, CONNECTION_RESTORED }
	public enum ScreenRule { NONE, WARNING, CLOSED }
	public static boolean expired(AttemptStatus status, Instant deadline, Instant now) {
		return status == AttemptStatus.IN_PROGRESS && deadline != null && !now.isBefore(deadline);
	}
	public static ScreenRule screenRule(EventKind event, int priorScreenLeftCount) {
		if (event != EventKind.SCREEN_LEFT) return ScreenRule.NONE;
		return priorScreenLeftCount == 0 ? ScreenRule.WARNING : ScreenRule.CLOSED;
	}
}
