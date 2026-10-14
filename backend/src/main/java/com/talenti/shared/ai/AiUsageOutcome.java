package com.talenti.shared.ai;

/** Resultado de un intento de uso de IA (009-31). Solo {@code SUCCESS} cuenta para los cupos. */
public enum AiUsageOutcome {

	SUCCESS,
	FAILED_PLATFORM,
	BLOCKED_PERSONAL_QUOTA,
	BLOCKED_GLOBAL_QUOTA

}
