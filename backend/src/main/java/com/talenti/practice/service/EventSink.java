package com.talenti.practice.service;

import java.util.Map;

/**
 * Por dónde salen los eventos de un turno (en la web, un flujo SSE). Si la persona se desconecta, {@link #send}
 * devuelve falso y el turno sigue hasta guardar lo suyo, sin emitir más (spec 012, sección 4).
 */
public interface EventSink {

	/** Emite un evento; falso si ya no hay quién lo reciba. Nunca lanza. */
	boolean send(String event, Map<String, Object> data);

	/** Si la persona se desconectó durante el flujo. */
	boolean disconnected();

	/** Cierra el flujo. Nunca lanza. */
	void complete();

}
