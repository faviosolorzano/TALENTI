package com.talenti.practice.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;

/**
 * Cuánto puede durar una llamada en tiempo real según el RELOJ DEL SERVIDOR y el gasto restante (012-49):
 * {@code endsAt = ahora + min(duración máxima del modo, minutos que alcanzan con el gasto restante)} y
 * {@code warnAt = endsAt − aviso}. Una reconexión nunca alarga el fin ya fijado. Código puro.
 */
public final class CallBudget {

	/** El resultado: si alcanza, y cuándo avisar y terminar. */
	public record Plan(boolean affordable, Instant endsAt, Instant warnAt) {
	}

	private CallBudget() {
	}

	/**
	 * @param remainingMicros gasto que queda del mes (millonésimas de dólar), ya descontado lo reservado por otras
	 *                        llamadas abiertas
	 * @param pricePerMinute  precio estimado por minuto en dólares; cero o menos = sin límite por gasto
	 * @param minDuration     por debajo de esto no vale la pena conectar ({@code min-minutes})
	 * @param previousEndsAt  fin ya fijado en una llamada anterior de la misma sesión; nulo en la primera
	 */
	public static Plan plan(Instant now, Duration maxDuration, long remainingMicros, BigDecimal pricePerMinute,
			Duration minDuration, Duration warnBefore, Instant previousEndsAt) {
		Duration allowed = maxDuration;
		if (pricePerMinute != null && pricePerMinute.signum() > 0) {
			long pricePerMinuteMicros = pricePerMinute.movePointRight(6).setScale(0, RoundingMode.CEILING).longValue();
			long affordableSeconds = BigDecimal.valueOf(Math.max(0, remainingMicros)).multiply(BigDecimal.valueOf(60))
					.divide(BigDecimal.valueOf(Math.max(1, pricePerMinuteMicros)), 0, RoundingMode.FLOOR).longValue();
			Duration affordable = Duration.ofSeconds(affordableSeconds);
			if (affordable.compareTo(minDuration) < 0) {
				return new Plan(false, null, null);
			}
			if (affordable.compareTo(allowed) < 0) {
				allowed = affordable;
			}
		}
		Instant endsAt = now.plus(allowed);
		if (previousEndsAt != null && previousEndsAt.isBefore(endsAt)) {
			endsAt = previousEndsAt;
		}
		if (!endsAt.isAfter(now)) {
			return new Plan(false, null, null);
		}
		Instant warnAt = endsAt.minus(warnBefore);
		if (warnAt.isBefore(now)) {
			warnAt = now;
		}
		return new Plan(true, endsAt, warnAt);
	}

	/** Costo estimado en millonésimas de dólar: {@code segundos × precio por minuto / 60}, redondeado hacia arriba. */
	public static long costMicros(long seconds, BigDecimal pricePerMinute) {
		if (pricePerMinute == null || pricePerMinute.signum() <= 0 || seconds <= 0) {
			return 0;
		}
		return pricePerMinute.movePointRight(6).multiply(BigDecimal.valueOf(seconds))
				.divide(BigDecimal.valueOf(60), 0, RoundingMode.CEILING).longValue();
	}

}
