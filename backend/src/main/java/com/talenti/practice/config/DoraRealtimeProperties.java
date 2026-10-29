package com.talenti.practice.config;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * DORA en tiempo real ({@code talenti.ai.dora.realtime.*}, spec 012 enmienda 2). APAGADA por defecto. URL, rutas,
 * modelo, voz, transcripción, detección de turnos y nombres de eventos son configuración y están A CONFIRMAR en la
 * prueba manual 012-69: si algo difiere, se cambia aquí y no en el código.
 *
 * @param enabled              usar OpenAI en tiempo real; exige {@code talenti.ai.dora.enabled=true}, la clave,
 *                             {@code base-url}, {@code model} y {@code voice} (si falta algo, el arranque falla)
 * @param simulated            SOLO pruebas y recorridos locales: ofrece el tiempo real con el adaptador SIMULADO (sin
 *                             red). Por defecto falso: con el proveedor simulado el tiempo real no está disponible
 *                             (012-38)
 * @param baseUrl              URL base del proveedor
 * @param callsPath            ruta para crear la llamada (multipart {@code sdp} + {@code session})
 * @param hangupPath           ruta para colgar; {@code {callId}} se reemplaza por el id del proveedor
 * @param model                modelo de voz en tiempo real (D-63)
 * @param voice                voz femenina y cálida (D-14, duda E2-2)
 * @param transcriptionModel   modelo que transcribe a la persona (duda E2-3)
 * @param transcriptionLanguage idioma de la transcripción
 * @param turnDetection        tipo de detección de turnos (duda E2-4); la interrupción siempre va activada
 * @param connectTimeout       tiempo máximo para abrir la llamada; después 503 {@code realtime-unavailable} (012-42)
 * @param maxSdp               tamaño máximo de la oferta SDP (012-43)
 * @param pricePerMinute       precio ESTIMADO por minuto en dólares (duda E2-6, a confirmar con el panel)
 * @param minMinutes           si el gasto restante alcanza menos minutos, no se conecta (012-49)
 * @param maxDuration          duración máxima por modo (duda E2-5)
 * @param warnBefore           aviso antes del fin
 * @param grace                margen tras el fin antes de colgar desde el servidor
 * @param maxReconnects        reconexiones por sesión (012-52)
 * @param followUpsPerQuestion repreguntas por pregunta (012-45)
 * @param connectsPerMinute    conexiones por minuto por persona (012-43)
 * @param eventsPerMinute      envíos de eventos por minuto por persona
 * @param maxEventsPerRequest  eventos por envío (012-44)
 * @param maxText              caracteres de una intervención (012-44)
 * @param maxAnswer            caracteres de una respuesta armada y de su corrección (duda E2-11)
 * @param sweepInterval        cada cuánto se buscan llamadas pasadas de su tiempo
 * @param events               nombres del canal y de los eventos del proveedor (duda E2-1)
 */
@ConfigurationProperties(prefix = "talenti.ai.dora.realtime")
public record DoraRealtimeProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue("false") boolean simulated,
		@DefaultValue("https://api.openai.com/v1") String baseUrl,
		@DefaultValue("/realtime/calls") String callsPath,
		@DefaultValue("/realtime/calls/{callId}/hangup") String hangupPath,
		@DefaultValue("gpt-realtime-2.1") String model,
		@DefaultValue("marin") String voice,
		@DefaultValue("gpt-4o-mini-transcribe") String transcriptionModel,
		@DefaultValue("es") String transcriptionLanguage,
		@DefaultValue("semantic_vad") String turnDetection,
		@DefaultValue("8s") Duration connectTimeout,
		@DefaultValue("32KB") DataSize maxSdp,
		@DefaultValue("0.30") BigDecimal pricePerMinute,
		@DefaultValue("3") int minMinutes,
		@DefaultValue MaxDuration maxDuration,
		@DefaultValue("2m") Duration warnBefore,
		@DefaultValue("30s") Duration grace,
		@DefaultValue("2") int maxReconnects,
		@DefaultValue("1") int followUpsPerQuestion,
		@DefaultValue("5") int connectsPerMinute,
		@DefaultValue("120") int eventsPerMinute,
		@DefaultValue("50") int maxEventsPerRequest,
		@DefaultValue("4000") int maxText,
		@DefaultValue("8000") int maxAnswer,
		@DefaultValue("10s") Duration sweepInterval,
		@DefaultValue Events events) {

	/** Duración máxima de la conversación por modo. */
	public record MaxDuration(
			@DefaultValue("15m") Duration practice,
			@DefaultValue("20m") Duration interview) {
	}

	/** Nombres del canal de datos y de los eventos (a confirmar en 012-69). */
	public record Events(
			@DefaultValue("oai-events") String channel,
			@DefaultValue("conversation.item.input_audio_transcription.completed") String personTranscript,
			@DefaultValue("response.output_audio_transcript.done") String doraTranscript,
			@DefaultValue("input_audio_buffer.speech_started") String speechStarted,
			@DefaultValue("input_audio_buffer.speech_stopped") String speechStopped,
			@DefaultValue("response.function_call_arguments.done") String functionCall,
			@DefaultValue("response.done") String responseDone) {
	}

}
