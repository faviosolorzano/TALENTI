package com.talenti.shared.ai;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Topes de consumo de IA ({@code talenti.ai.limits.*}, 009-28, 009-29, 012-29, 012-30). Todo es configurable.
 *
 * @param perUserMonthly tope por persona y mes calendario por función ({@code cv-read}, {@code practice-session}...);
 *                       una función sin entrada no tiene tope personal
 * @param globalDaily    usos completados por día entre todas las personas
 * @param globalMonthly  usos completados por mes entre todas las personas
 * @param zone           zona que define el día y el mes calendario
 * @param monthlySpend   tope del costo estimado del mes, en dólares, entre todas las personas y funciones (012-30);
 *                       lo fija Favio al cargar la clave
 */
@ConfigurationProperties(prefix = "talenti.ai.limits")
public record AiLimitsProperties(
		@DefaultValue Map<String, Integer> perUserMonthly,
		@DefaultValue("100") int globalDaily,
		@DefaultValue("1500") int globalMonthly,
		@DefaultValue("America/Lima") ZoneId zone,
		@DefaultValue("5") BigDecimal monthlySpend) {

	public AiLimitsProperties {
		perUserMonthly = Map.copyOf(perUserMonthly);
		if (globalDaily < 0 || globalMonthly < 0) {
			throw new IllegalArgumentException("talenti.ai.limits: los topes globales no pueden ser negativos");
		}
		if (monthlySpend == null || monthlySpend.signum() < 0) {
			throw new IllegalArgumentException("talenti.ai.limits.monthly-spend no puede ser negativo");
		}
	}

	/** El tope de gasto en millonésimas de dólar. */
	long monthlySpendMicros() {
		return monthlySpend.movePointRight(6).longValue();
	}

}
