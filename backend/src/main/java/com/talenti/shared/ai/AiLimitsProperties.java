package com.talenti.shared.ai;

import java.time.ZoneId;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Topes de consumo de IA ({@code talenti.ai.limits.*}, 009-28, 009-29). Todo es configurable.
 *
 * @param perUserMonthly tope por persona y mes calendario por función ({@code cv-read}, {@code practice-session}...);
 *                       una función sin entrada no tiene tope personal
 * @param globalDaily    usos completados por día entre todas las personas
 * @param globalMonthly  usos completados por mes entre todas las personas
 * @param zone           zona que define el día y el mes calendario
 */
@ConfigurationProperties(prefix = "talenti.ai.limits")
public record AiLimitsProperties(
		@DefaultValue Map<String, Integer> perUserMonthly,
		@DefaultValue("100") int globalDaily,
		@DefaultValue("1500") int globalMonthly,
		@DefaultValue("America/Lima") ZoneId zone) {

	public AiLimitsProperties {
		perUserMonthly = Map.copyOf(perUserMonthly);
		if (globalDaily < 0 || globalMonthly < 0) {
			throw new IllegalArgumentException("talenti.ai.limits: los topes globales no pueden ser negativos");
		}
	}

}
