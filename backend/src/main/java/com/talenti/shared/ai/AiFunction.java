package com.talenti.shared.ai;

import java.util.Locale;

/** Para qué se usa la IA o la voz (columna {@code function} de {@code ai_usage_event}). */
public enum AiFunction {

	CV_READ,
	PRACTICE_SESSION,
	PRACTICE_FEEDBACK,
	EXPLANATION,
	STT,
	TTS,
	/** Cada llamada de DORA a la IA para conversar: plan de preguntas y frase puente (spec 012). */
	DORA_DIALOG,
	/** Una entrevista evaluada (spec 012): no usa cupo personal, solo el tope global de gasto (012-28). */
	INTERVIEW_SESSION,
	/** Evaluación por rúbrica de una respuesta de entrevista (012-25). */
	INTERVIEW_EVALUATION,
	EXAM_GENERATION,
	EXAM_GRADING,
	/** Una llamada de DORA en tiempo real (spec 012, enmienda 2): segundos medidos por el servidor y costo estimado. */
	DORA_REALTIME;

	/** La clave de esta función en {@code talenti.ai.limits.per-user-monthly}, por ejemplo {@code cv-read}. */
	public String configKey() {
		return name().toLowerCase(Locale.ROOT).replace('_', '-');
	}

}
