package com.talenti.cv.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros de IA del lector de CV ({@code talenti.ai.*}, spec 009 sección 3.1). Todo está apagado por defecto:
 * la IA real no se enciende sin su implementación, su modelo y su clave (el arranque falla si falta algo).
 *
 * @param provider    {@code simulated} (por defecto) u {@code openai}; viene de la variable {@code AI_PROVIDER}
 * @param reuseWindow tiempo en que una misma huella ya leída con IA no se vuelve a enviar (009-26)
 */
@ConfigurationProperties(prefix = "talenti.ai")
public record AiCvProperties(
		@DefaultValue("simulated") String provider,
		@DefaultValue("30d") Duration reuseWindow,
		@DefaultValue Cv cv,
		@DefaultValue OpenAi openai) {

	/**
	 * @param enabled         enciende la lectura mejorada (009-27); falso hasta S10
	 * @param model           modelo más económico que cumpla (D-12); sin valor por defecto a propósito
	 * @param maxInputChars   caracteres máximos que salen hacia la IA (009-30)
	 * @param maxOutputTokens tope de la salida de la IA
	 * @param timeout         tiempo máximo de la llamada a la IA (009-29)
	 */
	public record Cv(
			@DefaultValue("false") boolean enabled,
			@DefaultValue("") String model,
			@DefaultValue("12000") int maxInputChars,
			@DefaultValue("3000") int maxOutputTokens,
			@DefaultValue("30s") Duration timeout) {
	}

	/** La clave llega SOLO por la variable de entorno {@code OPENAI_API_KEY}; nunca va en el código ni en imágenes. */
	public record OpenAi(
			@DefaultValue("") String apiKey,
			@DefaultValue("https://api.openai.com/v1") String baseUrl) {

		@Override
		public String toString() {
			return "OpenAi[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "no" : "***") + "]";
		}

	}

}
