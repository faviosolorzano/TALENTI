package com.talenti.practice.service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.talenti.practice.config.DoraProperties;
import com.talenti.practice.port.SpokenAudio;

/**
 * Estado EN MEMORIA de las sesiones abiertas (un solo servidor en el E2):
 * <ul>
 * <li>el candado de cada sesión: un turno en curso bloquea otro (409 {@code turn-in-progress}, 012-14);</li>
 * <li>el audio de los textos fijos de DORA (saludo y preguntas), sintetizado por adelantado mientras la persona responde
 * (012-10). Solo vive aquí, durante la sesión: nunca se guarda en la base;</li>
 * <li>el límite de turnos por minuto por persona (012-32).</li>
 * </ul>
 */
@Component
public class LiveSessions {

	private static final long MINUTE_NANOS = 60_000_000_000L;

	private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

	private final Map<UUID, Map<String, CompletableFuture<Optional<SpokenAudio>>>> audio = new ConcurrentHashMap<>();

	private final Map<UUID, Deque<Long>> turnsByUser = new ConcurrentHashMap<>();

	private final ExecutorService executor;

	private final int turnsPerMinute;

	LiveSessions(@Qualifier("doraExecutor") ExecutorService executor, DoraProperties properties) {
		this.executor = executor;
		this.turnsPerMinute = properties.turnsPerMinute();
	}

	/** Toma el candado de la sesión; falso si ya hay un turno en curso. */
	public boolean tryLock(UUID sessionId) {
		return busy.add(sessionId);
	}

	public void unlock(UUID sessionId) {
		busy.remove(sessionId);
	}

	public boolean isBusy(UUID sessionId) {
		return busy.contains(sessionId);
	}

	/** Cuenta un turno de la persona; falso si ya hizo el máximo en el último minuto. */
	public boolean allowTurn(UUID userId) {
		long now = System.nanoTime();
		Deque<Long> times = turnsByUser.computeIfAbsent(userId, id -> new ArrayDeque<>());
		synchronized (times) {
			while (!times.isEmpty() && now - times.peekFirst() > MINUTE_NANOS) {
				times.pollFirst();
			}
			if (times.size() >= turnsPerMinute) {
				return false;
			}
			times.addLast(now);
			return true;
		}
	}

	/** Empieza a sintetizar el audio de {@code key} si aún no está (o si el intento anterior falló). */
	public CompletableFuture<Optional<SpokenAudio>> prefetch(UUID sessionId, String key,
			Supplier<Optional<SpokenAudio>> synthesis) {
		Map<String, CompletableFuture<Optional<SpokenAudio>>> cache = audio.computeIfAbsent(sessionId,
				id -> new ConcurrentHashMap<>());
		return cache.compute(key, (k, existing) -> {
			if (existing != null && !(existing.isDone() && existing.getNow(Optional.empty()).isEmpty())) {
				return existing;
			}
			return CompletableFuture.supplyAsync(synthesis, executor).exceptionally(failure -> Optional.empty());
		});
	}

	/** El audio ya pedido para {@code key}, si se pidió. */
	public Optional<CompletableFuture<Optional<SpokenAudio>>> audio(UUID sessionId, String key) {
		return Optional.ofNullable(audio.getOrDefault(sessionId, Map.of()).get(key));
	}

	/** Olvida todo lo de la sesión (al cerrarla o borrarla). */
	public void forget(UUID sessionId) {
		audio.remove(sessionId);
		busy.remove(sessionId);
	}

}
