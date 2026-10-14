package com.talenti.cv.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Límite de solicitudes de subida y lectura por origen (009-07, RNF-05): ventana fija de un minuto, en memoria del
 * servidor. Pasado el máximo responde 429 {@code cv-limit-reached} con {@code Retry-After}.
 */
public class OriginRateLimiter {

	private static final int CLEANUP_THRESHOLD = 10_000;

	private record Window(Instant end, int count) {
	}

	private final Map<String, Window> windows = new ConcurrentHashMap<>();

	private final int maxRequests;

	private final Duration windowLength;

	private final Clock clock;

	public OriginRateLimiter(int maxRequests, Duration windowLength, Clock clock) {
		this.maxRequests = maxRequests;
		this.windowLength = windowLength;
		this.clock = clock;
	}

	public void check(String origin) {
		Instant now = clock.instant();
		Window window = windows.compute(origin, (key, current) -> current == null || !now.isBefore(current.end())
				? new Window(now.plus(windowLength), 1)
				: new Window(current.end(), current.count() + 1));
		if (windows.size() > CLEANUP_THRESHOLD) {
			windows.values().removeIf(w -> !now.isBefore(w.end()));
		}
		if (window.count() > maxRequests) {
			throw CvErrors.limitReached(Duration.between(now, window.end()).toSeconds() + 1);
		}
	}

}
