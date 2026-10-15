package com.talenti.cv.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.talenti.cv.config.CvProperties;

/**
 * Cuenta las subidas FALLIDAS (PDF ilegible) por persona en 24 horas (009-07): un PDF inválido cuesta lectura pero
 * no crea análisis, así que el límite diario de análisis no lo veía. Está en memoria (basta para un servidor; al
 * reiniciar se reinicia) y solo guarda ids y horas, nunca contenido.
 */
@Component
public class FailedUploadLimiter {

	private static final Duration WINDOW = Duration.ofHours(24);

	private final Map<UUID, Deque<Instant>> failures = new ConcurrentHashMap<>();

	private final CvProperties properties;

	private final Clock clock;

	FailedUploadLimiter(CvProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	/** Segundos hasta que se libere un intento, si la persona ya llegó al máximo; vacío si puede intentar. */
	public OptionalLong blockedFor(UUID userId) {
		Deque<Instant> deque = failures.get(userId);
		if (deque == null) {
			return OptionalLong.empty();
		}
		Instant now = clock.instant();
		synchronized (deque) {
			prune(deque, now);
			if (deque.size() >= properties.failedAttemptsPerDay()) {
				return OptionalLong.of(Duration.between(now, deque.peekFirst().plus(WINDOW)).toSeconds() + 1);
			}
		}
		return OptionalLong.empty();
	}

	public void recordFailure(UUID userId) {
		Instant now = clock.instant();
		Deque<Instant> deque = failures.computeIfAbsent(userId, id -> new ArrayDeque<>());
		synchronized (deque) {
			prune(deque, now);
			deque.addLast(now);
		}
		if (failures.size() > 10_000) {
			failures.entrySet().removeIf(entry -> {
				synchronized (entry.getValue()) {
					prune(entry.getValue(), now);
					return entry.getValue().isEmpty();
				}
			});
		}
	}

	private static void prune(Deque<Instant> deque, Instant now) {
		Instant limit = now.minus(WINDOW);
		while (!deque.isEmpty() && deque.peekFirst().isBefore(limit)) {
			deque.removeFirst();
		}
	}

}
