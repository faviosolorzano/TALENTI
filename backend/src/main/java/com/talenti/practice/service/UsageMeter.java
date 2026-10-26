package com.talenti.practice.service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.talenti.practice.config.DoraAiProperties;
import com.talenti.practice.domain.SessionMode;
import com.talenti.practice.port.ConversationPort;
import com.talenti.practice.port.ConversationPort.CallKind;
import com.talenti.shared.ai.AiFunction;
import com.talenti.shared.ai.AiLevel;
import com.talenti.shared.ai.AiUsageEvent;
import com.talenti.shared.ai.AiUsageOutcome;
import com.talenti.shared.ai.AiUsageQuota;
import com.talenti.shared.ai.AiUsageRecorder;

/**
 * Mide cada llamada de DORA en {@code ai_usage_event} (012-29, 012-30) por las interfaces públicas de shared: una fila
 * por llamada (id propio) o por sesión (el id de la sesión), con tamaños y costo estimado, NUNCA contenido. Anota fuera
 * del camino de la voz (en un hilo aparte) para no sumar tiempo al turno.
 */
@Component
public class UsageMeter {

	private static final Logger log = LoggerFactory.getLogger(UsageMeter.class);

	private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

	private final AiUsageRecorder recorder;

	private final AiUsageQuota quota;

	private final DoraAiProperties.Prices prices;

	private final ExecutorService executor;

	private final String adapter;

	private final AiLevel level;

	/** Pocas escrituras de medición a la vez: nunca acaparan las conexiones que necesita la conversación. */
	private final java.util.concurrent.Semaphore writers = new java.util.concurrent.Semaphore(2);

	UsageMeter(AiUsageRecorder recorder, AiUsageQuota quota, DoraAiProperties ai,
			@Qualifier("doraExecutor") ExecutorService executor, ConversationPort conversation) {
		this.recorder = recorder;
		this.quota = quota;
		this.prices = ai.prices();
		this.executor = executor;
		this.adapter = conversation.info().adapter();
		this.level = ai.enabled() ? AiLevel.ENHANCED : AiLevel.BASIC;
	}

	/** Cuánto vale la última consulta del tope de gasto (para no consultar la base en cada paso de un turno). */
	private static final long SPEND_CACHE_NANOS = 2_000_000_000L;

	private volatile long spendCheckedAt;

	private volatile boolean spendCached;

	/** Si el tope mensual de gasto ya se alcanzó: todo se degrada (012-30). Se recuerda unos 2 segundos. */
	public boolean spendReached() {
		long now = System.nanoTime();
		if (spendCheckedAt != 0 && now - spendCheckedAt < SPEND_CACHE_NANOS) {
			return spendCached;
		}
		try {
			spendCached = quota.monthlySpendReached();
			spendCheckedAt = now;
			return spendCached;
		}
		catch (RuntimeException e) {
			log.warn("No se pudo consultar el tope de gasto de IA ({}); se degrada por precaución", e.getClass().getName());
			return true;
		}
	}

	/** Olvida la última consulta del tope de gasto (las pruebas lo cambian a propósito). */
	public void refreshSpend() {
		spendCheckedAt = 0;
	}

	/** El registro de llamadas de un turno o de un texto fijo de DORA, para la persona. */
	public ConversationPort.CallLog callLog(UUID userId) {
		return (kind, model, inputSize, outputSize, success) -> call(userId, function(kind), model, inputSize,
				outputSize, success, cost(kind, inputSize, outputSize));
	}

	/** Una llamada directa al modelo (plan, retroalimentación, evaluación). */
	public void llm(UUID userId, AiFunction function, String model, int inputChars, int outputChars, boolean success) {
		call(userId, function, model, inputChars, outputChars, success, cost(CallKind.DIALOG, inputChars, outputChars));
	}

	/**
	 * La sesión cuenta como usada al responder su primera pregunta (012-29), con su id como clave: se anota una sola
	 * vez. La entrevista se anota como {@code INTERVIEW_SESSION}, que no tiene cupo personal (012-28).
	 */
	public void sessionUsed(UUID sessionId, UUID userId, SessionMode mode, AiUsageOutcome outcome) {
		AiFunction function = mode == SessionMode.INTERVIEW ? AiFunction.INTERVIEW_SESSION : AiFunction.PRACTICE_SESSION;
		boolean failed = outcome == AiUsageOutcome.FAILED_PLATFORM;
		UUID eventId = failed ? UUID.nameUUIDFromBytes(("session-failure:" + sessionId)
				.getBytes(java.nio.charset.StandardCharsets.UTF_8)) : sessionId;
		recordSafely(new AiUsageEvent(eventId, userId, function, level, adapter, null, outcome, 0, 0, null,
				failed ? sessionId : null));
	}

	private void call(UUID userId, AiFunction function, String model, int inputSize, int outputSize, boolean success,
			long costMicros) {
		AiUsageEvent event = new AiUsageEvent(UUID.randomUUID(), userId, function, level, adapter, model,
				success ? AiUsageOutcome.SUCCESS : AiUsageOutcome.FAILED_PLATFORM, inputSize, outputSize,
				success ? costMicros : 0L);
		executor.execute(() -> recordSafely(event));
	}

	private void record(AiUsageEvent event) {
		try {
			writers.acquire();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return;
		}
		try {
			recorder.record(event);
		}
		finally {
			writers.release();
		}
	}

	private void recordSafely(AiUsageEvent event) {
		try {
			record(event);
		}
		catch (RuntimeException e) {
			// La medición nunca corta la conversación; solo el tipo de error (sin contenido).
			log.warn("No se pudo anotar el consumo de IA: {}", e.getClass().getName());
		}
	}

	private static AiFunction function(CallKind kind) {
		return switch (kind) {
			case DIALOG -> AiFunction.DORA_DIALOG;
			case STT -> AiFunction.STT;
			case TTS -> AiFunction.TTS;
		};
	}

	/**
	 * Costo estimado en millonésimas de dólar. Modelo: tokens ≈ caracteres / 4 por precio por millón de tokens; voz a
	 * texto por minuto de audio; texto a voz por millón de caracteres. Los precios son configuración (a confirmar).
	 */
	long cost(CallKind kind, int inputSize, int outputSize) {
		BigDecimal usd = switch (kind) {
			case DIALOG -> prices.dialogInputPerMillionTokens().multiply(tokens(inputSize))
					.add(prices.dialogOutputPerMillionTokens().multiply(tokens(outputSize))).divide(MILLION);
			case STT -> prices.sttPerMinute().multiply(BigDecimal.valueOf(Math.max(0, inputSize)))
					.divide(BigDecimal.valueOf(60_000), 9, java.math.RoundingMode.HALF_UP);
			case TTS -> prices.ttsPerMillionChars().multiply(BigDecimal.valueOf(Math.max(0, inputSize))).divide(MILLION);
		};
		return usd.movePointRight(6).setScale(0, java.math.RoundingMode.CEILING).longValue();
	}

	private static BigDecimal tokens(int chars) {
		return BigDecimal.valueOf((Math.max(0, chars) + 3) / 4);
	}

}
