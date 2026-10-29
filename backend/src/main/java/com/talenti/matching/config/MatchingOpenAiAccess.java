package com.talenti.matching.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * La misma cuenta de OpenAI que el resto ({@code talenti.ai.openai.*}), leída por matching sin usar las clases de
 * practice (T5). La clave llega SOLO por la variable {@code OPENAI_API_KEY}; {@code toString} la oculta.
 */
@ConfigurationProperties(prefix = "talenti.ai.openai")
public record MatchingOpenAiAccess(
		@DefaultValue("") String apiKey,
		@DefaultValue("https://api.openai.com/v1") String baseUrl) {

	@Override
	public String toString() {
		return "MatchingOpenAiAccess[baseUrl=" + baseUrl + ", apiKey="
				+ (apiKey == null || apiKey.isBlank() ? "no" : "***") + "]";
	}

}
