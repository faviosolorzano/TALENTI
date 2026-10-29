package com.talenti.practice.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Límite por persona en una ventana deslizante de un minuto (en memoria; un solo servidor en el E2). */
final class RateLimiter {

	private static final long MINUTE_NANOS = 60_000_000_000L;

	private final Map<UUID, Deque<Long>> byUser = new ConcurrentHashMap<>();

	private final int perMinute;

	RateLimiter(int perMinute) {
		this.perMinute = perMinute;
	}

	/** Cuenta un uso; falso si ya hizo el máximo en el último minuto. */
	boolean allow(UUID userId) {
		long now = System.nanoTime();
		Deque<Long> times = byUser.computeIfAbsent(userId, id -> new ArrayDeque<>());
		synchronized (times) {
			while (!times.isEmpty() && now - times.peekFirst() > MINUTE_NANOS) {
				times.pollFirst();
			}
			if (times.size() >= perMinute) {
				return false;
			}
			times.addLast(now);
			return true;
		}
	}

	void reset() {
		byUser.clear();
	}

}
