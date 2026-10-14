package com.talenti.cv.service;

import java.time.Instant;

/** Respuesta de {@code GET /cv/usage} (009-27, 009-29, 009-35). */
public record UsageView(Basic basic, Enhanced enhanced) {

	public record Basic(boolean available) {
	}

	/**
	 * @param reason {@code DISABLED}, {@code PERSONAL_LIMIT} o {@code GLOBAL_LIMIT} cuando no está disponible
	 * @param limit  tope personal del mes; {@code -1} si no tiene
	 */
	public record Enhanced(boolean available, String reason, int used, int limit, Instant resetsAt) {
	}

}
