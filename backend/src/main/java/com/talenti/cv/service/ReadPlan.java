package com.talenti.cv.service;

import java.util.UUID;

import com.talenti.shared.ai.AiLevel;

/**
 * La decisión de qué nivel de lectura se hará, tomada ANTES de crear el trabajo (009-27 a 009-29).
 *
 * @param level          {@code ENHANCED} solo si la persona lo pidió y se cumplen la bandera, el cupo y la huella
 * @param readId         id de la lectura: es la clave de idempotencia del uso de IA (009-32)
 * @param degradedReason por qué se atiende con la lectura básica lo que se pidió mejorado; nulo si no se degradó
 */
public record ReadPlan(AiLevel level, UUID readId, String degradedReason) {

	public boolean degraded() {
		return degradedReason != null;
	}

}
