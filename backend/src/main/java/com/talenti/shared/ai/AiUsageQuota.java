package com.talenti.shared.ai;

import java.util.UUID;

/**
 * Interfaz pública del cupo de IA (009-28, 009-29, 009-34). Una sola fuente: la tabla {@code ai_usage_event}. Solo
 * cuentan los usos completados ({@code SUCCESS}); una falla de la plataforma o un bloqueo no se descuentan.
 */
public interface AiUsageQuota {

	/** Consulta sin anotar nada: primero el tope personal del mes y luego los globales (día y mes). */
	QuotaStatus check(UUID userId, AiFunction function);

	/**
	 * Si el costo estimado de este mes calendario (todas las personas y funciones) ya llegó al tope mensual de gasto
	 * ({@code talenti.ai.limits.monthly-spend}, 012-30). Al llegar, todo lo que usa IA o voz se degrada.
	 */
	boolean monthlySpendReached();

	/**
	 * Cuánto queda del tope mensual de gasto, en millonésimas de dólar (nunca negativo). Sirve para acotar de antemano la
	 * duración de una llamada en tiempo real (012-49).
	 */
	default long monthlySpendRemainingMicros() {
		return monthlySpendReached() ? 0 : Long.MAX_VALUE;
	}

}
