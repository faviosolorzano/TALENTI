package com.talenti.shared.ai;

import java.util.UUID;

/**
 * Interfaz pública para anotar el uso de IA (009-31, 009-34). Solo inserciones. La reutilizan la práctica con DORA
 * (S8) y la explicación con IA (S11) sin cambiar su forma.
 */
public interface AiUsageRecorder {

	/**
	 * Anota el intento. Es idempotente por {@link AiUsageEvent#readId()}: si esa lectura ya estaba anotada no hace
	 * nada y devuelve {@code false}.
	 */
	boolean record(AiUsageEvent event);

	/** Si esa lectura ya se anotó (para no volver a llamar a la IA tras un reintento, 009-32). */
	boolean exists(UUID readId);

}
