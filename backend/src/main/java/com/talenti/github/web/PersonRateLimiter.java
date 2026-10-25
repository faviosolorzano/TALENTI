package com.talenti.github.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.talenti.shared.web.TooManyRequestsException;

/**
 * Límite general de {@code /github} por persona (013-23): ventana fija de un minuto, en memoria del servidor. Pasado el
 * máximo responde 429 con {@code Retry-After}.
 */
final class PersonRateLimiter {

	private static final int CLEANUP_THRESHOLD = 10_000;

	private record Window(Instant end, int count) {
	}

	private final Map<UUID, Window> windows = new ConcurrentHashMap<>();

	private final int maxRequests;

	private final Clock clock;

	PersonRateLimiter(int maxRequests, Clock clock) {
		this.maxRequests = maxRequests;
		this.clock = clock;
	}

	void check(UUID userId) {
		Instant now = clock.instant();
		Window window = windows.compute(userId, (key, current) -> current == null || !now.isBefore(current.end())
				? new Window(now.plus(Duration.ofMinutes(1)), 1)
				: new Window(current.end(), current.count() + 1));
		if (windows.size() > CLEANUP_THRESHOLD) {
			windows.values().removeIf(w -> !now.isBefore(w.end()));
		}
		if (window.count() > maxRequests) {
			throw new TooManyRequestsException(Duration.between(now, window.end()).toSeconds() + 1);
		}
	}

}
