package com.talenti.github.client;

import java.time.Clock;
import java.time.Instant;
import java.util.OptionalInt;

/**
 * Presupuesto de llamadas a la API pública de GitHub (013-18, 013-19), en memoria del servidor: anota
 * {@code x-ratelimit-remaining} y {@code x-ratelimit-reset} de cada respuesta y, cuando quedan {@code reserve} o menos
 * (o GitHub respondió con límite), no deja llamar hasta el {@code reset}. Al reiniciar el servidor empieza de cero y
 * la primera respuesta lo vuelve a poner al día.
 */
public final class RateBudget {

	private final int reserve;

	private final Clock clock;

	private Integer remaining;

	private Instant reset;

	private Instant blockedUntil;

	public RateBudget(int reserve, Clock clock) {
		this.reserve = reserve;
		this.clock = clock;
	}

	/** Lanza {@link GithubRateLimitedException} si no se puede llamar ahora. */
	public synchronized void check() {
		Instant now = clock.instant();
		if (blockedUntil != null) {
			if (now.isBefore(blockedUntil)) {
				throw new GithubRateLimitedException(blockedUntil);
			}
			blockedUntil = null;
		}
		if (reset != null && !now.isBefore(reset)) {
			// Pasó la hora de reinicio: el siguiente presupuesto llega con la próxima respuesta.
			remaining = null;
			reset = null;
		}
		if (remaining != null && remaining <= reserve && reset != null) {
			throw new GithubRateLimitedException(reset);
		}
	}

	/** Anota lo que dijo GitHub en una respuesta (valores ausentes o mal formados se ignoran). */
	public synchronized void record(String remainingHeader, String resetHeader) {
		Integer parsedRemaining = parseInt(remainingHeader);
		Instant parsedReset = parseEpoch(resetHeader);
		if (parsedRemaining != null) {
			remaining = parsedRemaining;
		}
		if (parsedReset != null) {
			reset = parsedReset;
		}
	}

	/** GitHub respondió con límite: nadie llama hasta {@code until}. */
	public synchronized void block(Instant until) {
		blockedUntil = blockedUntil == null || until.isAfter(blockedUntil) ? until : blockedUntil;
	}

	/** Llamadas restantes según la última respuesta (para los registros); vacío si no se sabe. */
	public synchronized OptionalInt remaining() {
		return remaining == null ? OptionalInt.empty() : OptionalInt.of(remaining);
	}

	static Integer parseInt(String value) {
		if (value == null) {
			return null;
		}
		try {
			return Integer.valueOf(value.strip());
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

	static Instant parseEpoch(String value) {
		if (value == null) {
			return null;
		}
		try {
			return Instant.ofEpochSecond(Long.parseLong(value.strip()));
		}
		catch (NumberFormatException | java.time.DateTimeException e) {
			return null;
		}
	}

}
