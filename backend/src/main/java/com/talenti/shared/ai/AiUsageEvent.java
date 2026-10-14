package com.talenti.shared.ai;

import java.util.Objects;
import java.util.UUID;

/**
 * Un intento de uso de IA o voz. Es solo medición: NUNCA lleva contenido (ni texto del CV, ni sugerencias, ni la
 * huella del archivo), solo tamaños (009-31).
 *
 * @param readId     id de la lectura o sesión: clave de idempotencia (una lectura se anota una sola vez)
 * @param userId     la persona; puede quedar nulo si la cuenta se elimina después
 * @param model      nulo si el adaptador no tiene un modelo
 * @param inputSize  tamaño de la entrada (caracteres)
 * @param outputSize tamaño de la salida (caracteres)
 */
public record AiUsageEvent(UUID readId, UUID userId, AiFunction function, AiLevel level, String adapter, String model,
		AiUsageOutcome outcome, int inputSize, int outputSize) {

	public AiUsageEvent {
		Objects.requireNonNull(readId, "readId");
		Objects.requireNonNull(function, "function");
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(adapter, "adapter");
		Objects.requireNonNull(outcome, "outcome");
	}

}
