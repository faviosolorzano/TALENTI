package com.talenti.shared.ai;

import java.util.Objects;
import java.util.UUID;

/**
 * Un intento de uso de IA o voz. Es solo medición: NUNCA lleva contenido (ni texto del CV, ni sugerencias, ni la
 * huella del archivo, ni transcripciones, ni preguntas), solo tamaños (009-31, 012-30).
 *
 * @param readId              id de la lectura, de la sesión o de cada llamada: clave de idempotencia (se anota una sola
 *                            vez)
 * @param userId              la persona; puede quedar nulo si la cuenta se elimina después
 * @param model               nulo si el adaptador no tiene un modelo
 * @param inputSize           tamaño de la entrada (caracteres; en voz a texto, milisegundos de audio)
 * @param outputSize          tamaño de la salida (caracteres; en texto a voz, milisegundos de audio)
 * @param estimatedCostMicros costo estimado en millonésimas de dólar con los precios de la configuración; nulo si no
 *                            se estima
 * @param reversesReadId      uso de sesión compensado por una falla de plataforma; nulo en los demás eventos
 */
public record AiUsageEvent(UUID readId, UUID userId, AiFunction function, AiLevel level, String adapter, String model,
		AiUsageOutcome outcome, int inputSize, int outputSize, Long estimatedCostMicros, UUID reversesReadId) {

	public AiUsageEvent {
		Objects.requireNonNull(readId, "readId");
		Objects.requireNonNull(function, "function");
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(adapter, "adapter");
		Objects.requireNonNull(outcome, "outcome");
		if (estimatedCostMicros != null && estimatedCostMicros < 0) {
			throw new IllegalArgumentException("estimatedCostMicros no puede ser negativo");
		}
		if (reversesReadId != null && (outcome != AiUsageOutcome.FAILED_PLATFORM
				|| (function != AiFunction.PRACTICE_SESSION && function != AiFunction.INTERVIEW_SESSION)
				|| reversesReadId.equals(readId))) {
			throw new IllegalArgumentException("Solo una falla de sesión puede compensar otro uso");
		}
	}

	/** Uso sin compensación; conserva el contrato de los módulos existentes. */
	public AiUsageEvent(UUID readId, UUID userId, AiFunction function, AiLevel level, String adapter, String model,
			AiUsageOutcome outcome, int inputSize, int outputSize, Long estimatedCostMicros) {
		this(readId, userId, function, level, adapter, model, outcome, inputSize, outputSize, estimatedCostMicros, null);
	}

	/** Sin costo estimado (la lectura de CV, spec 009). */
	public AiUsageEvent(UUID readId, UUID userId, AiFunction function, AiLevel level, String adapter, String model,
			AiUsageOutcome outcome, int inputSize, int outputSize) {
		this(readId, userId, function, level, adapter, model, outcome, inputSize, outputSize, null);
	}

}
