package com.talenti.practice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * La misma cuenta de OpenAI que el lector de CV ({@code talenti.ai.openai.*}). La clave llega SOLO por la variable
 * {@code OPENAI_API_KEY}; nunca va en el código, en imágenes ni en registros ({@code toString} la oculta).
 */
@ConfigurationProperties(prefix = "talenti.ai.openai")
public record OpenAiAccess(
		@DefaultValue("") String apiKey,
		@DefaultValue("https://api.openai.com/v1") String baseUrl) {

	@Override
	public String toString() {
		return "OpenAiAccess[baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "no" : "***")
				+ "]";
	}

}
