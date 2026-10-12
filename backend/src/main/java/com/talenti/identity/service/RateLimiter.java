package com.talenti.identity.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.talenti.shared.web.TooManyRequestsException;

/**
 * Límite de solicitudes por origen (RNF-05, 001-11), con un cubo por instancia (ingreso y registro): ventana fija, en memoria del servidor.
 * Pasado el máximo dentro de la ventana responde 429 con {@code Retry-After} hasta que la ventana termina.
 * Al reiniciar el servidor los contadores empiezan de cero (basta para el E2, igual que la sesión).
 */
public class RateLimiter {

	/** Con más orígenes que esto se descartan las ventanas vencidas, para que la memoria no crezca sin límite. */
	private static final int CLEANUP_THRESHOLD = 10_000;

	private record Window(Instant end, int count) {
	}

	private final Map<String, Window> windows = new ConcurrentHashMap<>();

	private final int maxRequests;

	private final Duration windowLength;

	private final Clock clock;

	public RateLimiter(int maxRequests, Duration windowLength, Clock clock) {
		this.maxRequests = maxRequests;
		this.windowLength = windowLength;
		this.clock = clock;
	}

	/** Cuenta una solicitud del origen; lanza {@link TooManyRequestsException} si pasó el máximo. */
	public void check(String origin) {
		Instant now = clock.instant();
		Window window = windows.compute(origin, (key, current) -> current == null || !now.isBefore(current.end())
				? new Window(now.plus(windowLength), 1)
				: new Window(current.end(), current.count() + 1));
		if (windows.size() > CLEANUP_THRESHOLD) {
			windows.values().removeIf(w -> !now.isBefore(w.end()));
		}
		if (window.count() > maxRequests) {
			long seconds = Duration.between(now, window.end()).toSeconds() + 1;
			throw new TooManyRequestsException(seconds);
		}
	}

}
