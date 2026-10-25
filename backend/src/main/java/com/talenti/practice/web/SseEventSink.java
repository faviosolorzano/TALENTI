package com.talenti.practice.web;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.talenti.practice.service.EventSink;

import tools.jackson.databind.json.JsonMapper;

/**
 * Los eventos de DORA sobre un flujo SSE ({@link SseEmitter}, de Spring MVC: sin librerías nuevas). Cada evento se
 * escribe y se envía en cuanto ocurre. Si la persona se desconecta, deja de escribir sin cortar el turno. El contenido
 * nunca se registra.
 */
final class SseEventSink implements EventSink {

	private final SseEmitter emitter;

	private final JsonMapper mapper;

	private volatile boolean closed;

	private volatile boolean disconnected;

	SseEventSink(SseEmitter emitter, JsonMapper mapper) {
		this.emitter = emitter;
		this.mapper = mapper;
		emitter.onTimeout(() -> closed = true);
		emitter.onError(failure -> {
			closed = true;
			disconnected = true;
		});
	}

	@Override
	public synchronized boolean send(String event, Map<String, Object> data) {
		if (closed) {
			return false;
		}
		try {
			emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(data), MediaType.APPLICATION_JSON));
			return true;
		}
		catch (IOException | IllegalStateException failure) {
			closed = true;
			disconnected = true;
			return false;
		}
	}

	@Override
	public boolean disconnected() {
		return disconnected;
	}

	@Override
	public synchronized void complete() {
		if (!closed) {
			closed = true;
			try {
				emitter.complete();
			}
			catch (RuntimeException ignored) {
				// ya estaba cerrado
			}
		}
	}

}
