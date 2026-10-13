package com.talenti.profile.domain;

/**
 * Un número entero que llegó de la entrada. Distingue "no vino" (nulo) de "vino pero no es un entero" (un texto,
 * un decimal, un número enorme), para responder 422 en vez de adivinar. Lo construye la capa web.
 */
public record IntInput(Integer value, boolean malformed) {

	private static final IntInput ABSENT = new IntInput(null, false);

	private static final IntInput MALFORMED = new IntInput(null, true);

	public static IntInput absent() {
		return ABSENT;
	}

	public static IntInput notAnInteger() {
		return MALFORMED;
	}

	public static IntInput of(Integer value) {
		return value == null ? ABSENT : new IntInput(value, false);
	}

}
