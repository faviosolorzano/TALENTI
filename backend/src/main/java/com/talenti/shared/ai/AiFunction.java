package com.talenti.shared.ai;

import java.util.Locale;

/** Para qué se usa la IA o la voz (columna {@code function} de {@code ai_usage_event}). */
public enum AiFunction {

	CV_READ,
	PRACTICE_SESSION,
	PRACTICE_FEEDBACK,
	EXPLANATION,
	STT,
	TTS;

	/** La clave de esta función en {@code talenti.ai.limits.per-user-monthly}, por ejemplo {@code cv-read}. */
	public String configKey() {
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

}
