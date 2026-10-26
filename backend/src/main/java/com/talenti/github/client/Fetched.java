package com.talenti.github.client;

/**
 * Lo que devolvió GitHub con su {@code ETag}. Si {@code notModified} es verdadero (304 por {@code If-None-Match}),
 * {@code value} es nulo y se conserva lo guardado (013-09).
 */
public record Fetched<T>(T value, String etag, boolean notModified) {

	static <T> Fetched<T> unchanged(String etag) {
		return new Fetched<>(null, etag, true);
	}

	@Override
	public String toString() {
		return "Fetched[notModified=" + notModified + "]";
	}

}
