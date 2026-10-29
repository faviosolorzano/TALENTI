package com.talenti.matching.config;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * "DORA te explica" con IA ({@code talenti.ai.explanation.*}, 015-16). APAGADA por defecto: con {@code enabled=false}
 * se usa la plantilla y no sale nada hacia ningún servicio externo. Encendida exige {@code OPENAI_API_KEY} y el modelo
 * (si falta algo, el arranque falla con un mensaje claro). Modelo y precios son A CONFIRMAR con el proveedor.
 *
 * @param enabled         usar OpenAI (verdadero) o la plantilla (falso, por defecto)
 * @param simulated       SOLO pruebas y recorridos locales: con {@code enabled=false}, usar el adaptador simulado (sin
 *                        red) como si la IA estuviera encendida
 * @param model           modelo; sin valor por defecto a propósito (propuesto {@code gpt-4.1-mini}, D-71)
 * @param timeout         tiempo máximo de la llamada; después, plantilla con {@code TIMEOUT} (015-10)
 * @param maxOutputTokens tope de la salida
 */
@ConfigurationProperties(prefix = "talenti.ai.explanation")
public record ExplanationAiProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("false") boolean simulated,
		@DefaultValue("") String model,
		@DefaultValue("8s") Duration timeout,
		@DefaultValue("700") int maxOutputTokens,
		@DefaultValue Prices prices) {

	public ExplanationAiProperties {
		if (timeout == null || timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("talenti.ai.explanation.timeout debe ser positivo");
		}
		if (maxOutputTokens <= 0) {
			throw new IllegalArgumentException("talenti.ai.explanation.max-output-tokens debe ser positivo");
		}
	}

	/** Si la explicación se pide a una IA (real o simulada). */
	public boolean aiOn() {
		return enabled || simulated;
	}

	/**
	 * Precios en dólares por millón de tokens para ESTIMAR el costo (015-14). A CONFIRMAR con la página de precios del
	 * proveedor: son una estimación, no una factura. Los tokens se estiman como caracteres / 4.
	 */
	public record Prices(
			@DefaultValue("0.40") BigDecimal inputPerMillionTokens,
			@DefaultValue("1.60") BigDecimal outputPerMillionTokens) {

		public Prices {
			if (inputPerMillionTokens.signum() < 0 || outputPerMillionTokens.signum() < 0) {
				throw new IllegalArgumentException("talenti.ai.explanation.prices no pueden ser negativos");
			}
		}

	}

}
