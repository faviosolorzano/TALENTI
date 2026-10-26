package com.talenti.github.client;

import java.time.Instant;

/**
 * GitHub limita las consultas (013-19): quedan {@code reserve-calls} o menos, o respondió 403/429 de límite. No se le
 * vuelve a llamar hasta {@code retryAt}. El mensaje no lleva datos de personas.
 */
public class GithubRateLimitedException extends RuntimeException {

	private final Instant retryAt;

	public GithubRateLimitedException(Instant retryAt) {
		super("GitHub limita las consultas");
		this.retryAt = retryAt;
	}

	public Instant retryAt() {
		return retryAt;
	}

}
