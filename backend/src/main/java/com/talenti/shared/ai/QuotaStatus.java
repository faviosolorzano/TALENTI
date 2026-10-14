package com.talenti.shared.ai;

import java.time.Instant;

/**
 * Estado del cupo de una persona para una función (009-28, 009-29).
 *
 * @param blockedBy      nulo si puede usar la función; si no, quién la bloquea (el motivo que se muestra)
 * @param used           usos completados este mes por la persona
 * @param limit          tope personal del mes; {@code -1} si la función no tiene tope personal
 * @param personalResets cuándo se renueva el cupo personal (primer día del mes siguiente, hora de Lima)
 */
public record QuotaStatus(Block blockedBy, int used, int limit, Instant personalResets) {

	public enum Block {
		PERSONAL, GLOBAL
	}

	public boolean allowed() {
		return blockedBy == null;
	}

}
