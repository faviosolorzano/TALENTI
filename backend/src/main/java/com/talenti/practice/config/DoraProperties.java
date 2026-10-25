package com.talenti.practice.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Parámetros de DORA ({@code talenti.dora.*}, spec 012 sección 5). Todo es configuración, no código.
 *
 * @param questionTimeout   tiempo máximo para planificar las preguntas con la IA; después, banco base (012-04)
 * @param bridgeTimeout     tiempo máximo hasta el primer fragmento de la frase puente; después, plantilla (012-09)
 * @param bridgeMax         tiempo máximo total de la frase puente (si el modelo se queda a medias)
 * @param bridgeMaxTokens   tope de la salida de la frase puente (respuesta corta)
 * @param sttTimeout        tiempo máximo de la transcripción, con un reintento si queda tiempo (012-11)
 * @param ttsTimeout        tiempo máximo de cada síntesis de voz (012-11)
 * @param feedbackTimeout   tiempo máximo de la retroalimentación (012-19)
 * @param maxAudioBytes     tamaño máximo del audio de un turno (012-07)
 * @param maxAudio          duración máxima del audio de un turno (012-07)
 * @param maxTranscript     caracteres máximos de una transcripción (012-13)
 * @param inactivityTimeout sin actividad durante este tiempo, la sesión se cierra (012-14)
 * @param sweepInterval     cada cuánto se buscan sesiones inactivas
 * @param retryPerQuestion  veces que se puede repetir la última respuesta en la práctica (012-13, duda 11)
 * @param turnsPerMinute    turnos por minuto por persona (012-32)
 * @param maxQuestions      preguntas por sesión de práctica (RN-09)
 * @param rubricWeights     pesos de la rúbrica para el total (deben sumar 100)
 */
@ConfigurationProperties(prefix = "talenti.dora")
public record DoraProperties(
		@DefaultValue("8s") Duration questionTimeout,
		@DefaultValue("1500ms") Duration bridgeTimeout,
		@DefaultValue("6s") Duration bridgeMax,
		@DefaultValue("80") int bridgeMaxTokens,
		@DefaultValue("6s") Duration sttTimeout,
		@DefaultValue("4s") Duration ttsTimeout,
		@DefaultValue("15s") Duration feedbackTimeout,
		@DefaultValue("3MB") DataSize maxAudioBytes,
		@DefaultValue("120s") Duration maxAudio,
		@DefaultValue("4000") int maxTranscript,
		@DefaultValue("15m") Duration inactivityTimeout,
		@DefaultValue("1m") Duration sweepInterval,
		@DefaultValue("1") int retryPerQuestion,
		@DefaultValue("30") int turnsPerMinute,
		@DefaultValue("5") int maxQuestions,
		@DefaultValue Map<String, Integer> rubricWeights,
		@DefaultValue Interview interview) {

	public DoraProperties {
		rubricWeights = rubricWeights.isEmpty() ? Map.of("TECHNICAL_ACCURACY", 25, "CLARITY", 25, "STRUCTURE", 25,
				"EXAMPLES", 25) : Map.copyOf(rubricWeights);
		if (maxQuestions < 1 || maxQuestions > 5) {
			throw new IllegalArgumentException("talenti.dora.max-questions debe estar entre 1 y 5 (RN-09)");
		}
	}

	/**
	 * @param enabled       la entrevista evaluada está disponible para las postulaciones enviadas (duda 9)
	 * @param maxAttempts   intentos tras desconexión o falla (duda 10)
	 * @param questionCount preguntas de la entrevista
	 * @param noticeVersion versión vigente del aviso (classpath:practice/interview-notice-&lt;versión&gt;.md)
	 */
	public record Interview(
			@DefaultValue("true") boolean enabled,
			@DefaultValue("2") int maxAttempts,
			@DefaultValue("5") int questionCount,
			@DefaultValue("v1") String noticeVersion) {
	}

}
