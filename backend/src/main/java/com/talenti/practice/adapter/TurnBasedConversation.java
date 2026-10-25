package com.talenti.practice.adapter;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.talenti.practice.domain.SentenceSplitter;
import com.talenti.practice.port.ConversationPort;
import com.talenti.practice.port.LlmPort;
import com.talenti.practice.port.SpeechToTextPort;
import com.talenti.practice.port.SpokenAudio;
import com.talenti.practice.port.TextToSpeechPort;

/**
 * Adaptador POR TURNOS de {@link ConversationPort} (012-31): voz a texto → frase puente en streaming → voz por frases.
 * La fluidez manda (RNF-11, 012-15 a 012-17):
 * <ul>
 * <li>La transcripción tiene {@code stt-timeout}; si falla rápido y queda tiempo, se reintenta una vez.</li>
 * <li>La frase puente se pide en streaming: si el primer fragmento no llega en {@code bridge-timeout}, se usa la
 * plantilla. Cada frase completa va a la voz APENAS termina, así el primer audio sale antes de que el modelo acabe.</li>
 * <li>Las síntesis de cada frase arrancan en paralelo y los audios se entregan en orden; cada una tiene
 * {@code tts-timeout}. Si la voz falla o tarda, el turno sigue solo con texto ({@code TTS_UNAVAILABLE}).</li>
 * </ul>
 * Todas las esperas tienen un límite y todo hilo colgado se interrumpe: ningún turno queda sin cierre. Usa hilos
 * virtuales. No guarda ni registra contenido.
 */
public class TurnBasedConversation implements ConversationPort {

	public static final String ADAPTER = "turn-based";

	/** Si la primera transcripción falla antes de esto del final del plazo, no hay tiempo para reintentar. */
	private static final long MIN_RETRY_NANOS = Duration.ofMillis(500).toNanos();

	/**
	 * @param maxBridgeSentences frases máximas de la frase puente (respuesta corta)
	 * @param maxBridgeChars     caracteres máximos de la frase puente
	 */
	public record Timeouts(Duration stt, Duration bridgeFirst, Duration bridgeMax, Duration tts,
			int maxBridgeSentences, int maxBridgeChars) {
	}

	private final LlmPort llm;

	private final SpeechToTextPort stt;

	private final TextToSpeechPort tts;

	private final Timeouts timeouts;

	private final ExecutorService executor;

	public TurnBasedConversation(LlmPort llm, SpeechToTextPort stt, TextToSpeechPort tts, Timeouts timeouts,
			ExecutorService executor) {
		this.llm = llm;
		this.stt = stt;
		this.tts = tts;
		this.timeouts = timeouts;
		this.executor = executor;
	}

	@Override
	public Info info() {
		return new Info(ADAPTER + "/" + llm.adapter(), llm.model(), stt.model(), tts.model(), tts.voice(),
				llm.promptVersion());
	}

	@Override
	public void runTurn(TurnInput input, TurnListener listener) {
		String transcript;
		Long sttMs = null;
		Integer audioMs = input.audio() == null ? null : input.audio().durationMs();
		if (input.audio() != null) {
			long start = System.nanoTime();
			SpeechToTextPort.Transcription result = transcribe(input);
			if (result == null) {
				listener.onError("stt-failed", true);
				return;
			}
			sttMs = (System.nanoTime() - start) / 1_000_000;
			transcript = result.text() == null ? "" : result.text().strip();
			if (result.audioMs() != null) {
				audioMs = result.audioMs();
			}
			if (transcript.isEmpty()) {
				listener.onError("no-speech", true);
				return;
			}
		}
		else {
			transcript = input.text() == null ? "" : input.text().strip();
		}
		if (!listener.onTranscript(transcript, sttMs, audioMs)) {
			return;
		}
		Speaker speaker = new Speaker(input.speak() && input.aiAllowed(), listener, input.log());
		BridgeOutcome outcome = input.aiAllowed() ? bridge(input, listener, speaker)
				: template(input, listener, speaker);
		speaker.finish();
		listener.onDone(outcome);
	}

	@Override
	public Optional<SpokenAudio> speak(String text, CallLog log) {
		Future<SpokenAudio> future = executor.submit(() -> tts.synthesize(text));
		try {
			SpokenAudio audio = future.get(timeouts.tts().toNanos(), TimeUnit.NANOSECONDS);
			log.call(CallKind.TTS, tts.model(), text.length(), audio.audioMs(), true);
			return Optional.of(audio);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			future.cancel(true);
			return Optional.empty();
		}
		catch (ExecutionException | TimeoutException | RuntimeException e) {
			future.cancel(true);
			log.call(CallKind.TTS, tts.model(), text.length(), 0, false);
			return Optional.empty();
		}
	}

	// ------------------------------------------------------------------------------------------ voz a texto

	private SpeechToTextPort.Transcription transcribe(TurnInput input) {
		long deadline = System.nanoTime() + timeouts.stt().toNanos();
		int inputSize = input.audio().durationMs() == null ? 0 : input.audio().durationMs();
		for (int attempt = 0; attempt < 2; attempt++) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0 || (attempt > 0 && remaining < MIN_RETRY_NANOS)) {
				break;
			}
			Future<SpeechToTextPort.Transcription> future = executor
					.submit(() -> stt.transcribe(input.audio().data(), input.audio().mime()));
			try {
				SpeechToTextPort.Transcription result = future.get(remaining, TimeUnit.NANOSECONDS);
				input.log().call(CallKind.STT, stt.model(), inputSize, result.text() == null ? 0 : result.text().length(),
						true);
				return result;
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				future.cancel(true);
				return null;
			}
			catch (TimeoutException e) {
				future.cancel(true);
				input.log().call(CallKind.STT, stt.model(), inputSize, 0, false);
				return null;
			}
			catch (ExecutionException | RuntimeException e) {
				input.log().call(CallKind.STT, stt.model(), inputSize, 0, false);
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------ frase puente

	private static final Object END = new Object();

	private static final Object FAILURE = new Object();

	private BridgeOutcome bridge(TurnInput input, TurnListener listener, Speaker speaker) {
		LlmPort.BridgeRequest request = input.bridge();
		int inputSize = length(request.firstName()) + length(request.answeredQuestion()) + length(request.nextQuestion());
		BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
		Future<?> call = executor.submit(() -> {
			try {
				llm.streamBridge(request, queue::offer);
				queue.offer(END);
			}
			catch (Throwable failure) {
				queue.offer(FAILURE);
			}
		});
		long start = System.nanoTime();
		Object first = poll(queue, timeouts.bridgeFirst().toNanos());
		if (!(first instanceof String)) {
			call.cancel(true);
			input.log().call(CallKind.DIALOG, llm.model(), inputSize, 0, false);
			return template(input, listener, speaker);
		}
		SentenceSplitter splitter = new SentenceSplitter();
		Emitted emitted = new Emitted();
		long llmDone = 0;
		boolean success = true;
		emit(splitter.add((String) first), emitted, listener, speaker);
		long deadline = start + timeouts.bridgeMax().toNanos();
		while (emitted.count < timeouts.maxBridgeSentences()) {
			Object item = poll(queue, deadline - System.nanoTime());
			if (item == null || item == FAILURE) {
				success = item == null && emitted.count > 0;
				break;
			}
			if (item == END) {
				llmDone = System.nanoTime();
				break;
			}
			emit(splitter.add((String) item), emitted, listener, speaker);
		}
		if (emitted.count < timeouts.maxBridgeSentences()) {
			String rest = splitter.flush();
			if (!rest.isEmpty()) {
				emit(List.of(rest), emitted, listener, speaker);
			}
		}
		if (llmDone == 0) {
			call.cancel(true);
			llmDone = System.nanoTime();
		}
		input.log().call(CallKind.DIALOG, llm.model(), inputSize, emitted.chars, success || emitted.count > 0);
		if (emitted.count == 0) {
			return template(input, listener, speaker);
		}
		return new BridgeOutcome(ReplySource.AI, llmDone);
	}

	private static final class Emitted {

		int count;

		int chars;

	}

	private void emit(List<String> sentences, Emitted emitted, TurnListener listener, Speaker speaker) {
		for (String sentence : sentences) {
			if (emitted.count >= timeouts.maxBridgeSentences() || emitted.chars >= timeouts.maxBridgeChars()) {
				return;
			}
			String text = sentence.length() > timeouts.maxBridgeChars() - emitted.chars
					? sentence.substring(0, timeouts.maxBridgeChars() - emitted.chars).strip() : sentence;
			if (text.isEmpty()) {
				return;
			}
			emitted.count++;
			emitted.chars += text.length();
			listener.onReplyText(emitted.count, text, ReplySource.AI);
			speaker.say(emitted.count, text);
		}
	}

	private BridgeOutcome template(TurnInput input, TurnListener listener, Speaker speaker) {
		listener.onReplyText(1, input.templateBridge(), ReplySource.TEMPLATE);
		speaker.say(1, input.templateBridge());
		return new BridgeOutcome(ReplySource.TEMPLATE, 0);
	}

	private static Object poll(BlockingQueue<Object> queue, long nanos) {
		if (nanos <= 0) {
			return queue.poll();
		}
		try {
			return queue.poll(nanos, TimeUnit.NANOSECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	private static int length(String text) {
		return text == null ? 0 : text.length();
	}

	// ------------------------------------------------------------------------------------------ voz por frases

	/**
	 * Sintetiza cada frase en cuanto llega (en paralelo) y entrega los audios EN ORDEN. Si una síntesis falla o tarda
	 * más de {@code tts-timeout}, avisa una sola vez {@code TTS_UNAVAILABLE} y las demás frases quedan solo en texto.
	 */
	private final class Speaker {

		private final boolean enabled;

		private final TurnListener listener;

		private final CallLog log;

		private volatile boolean unavailable;

		private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

		Speaker(boolean enabled, TurnListener listener, CallLog log) {
			this.enabled = enabled;
			this.listener = listener;
			this.log = log;
		}

		synchronized void say(int seq, String text) {
			if (!enabled || unavailable) {
				return;
			}
			long submitted = System.nanoTime();
			Future<SpokenAudio> synthesis = executor.submit(() -> tts.synthesize(text));
			tail = tail.thenRunAsync(() -> deliver(seq, text, synthesis, submitted), executor);
		}

		private void deliver(int seq, String text, Future<SpokenAudio> synthesis, long submitted) {
			if (unavailable) {
				synthesis.cancel(true);
				return;
			}
			long remaining = submitted + timeouts.tts().toNanos() - System.nanoTime();
			try {
				SpokenAudio audio = synthesis.get(Math.max(0, remaining), TimeUnit.NANOSECONDS);
				log.call(CallKind.TTS, tts.model(), text.length(), audio.audioMs(), true);
				listener.onReplyAudio(seq, audio);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				synthesis.cancel(true);
			}
			catch (ExecutionException | TimeoutException | RuntimeException e) {
				synthesis.cancel(true);
				log.call(CallKind.TTS, tts.model(), text.length(), 0, false);
				unavailable = true;
				listener.onDegraded("TTS_UNAVAILABLE");
			}
		}

		void finish() {
			CompletableFuture<Void> last;
			synchronized (this) {
				last = tail;
			}
			try {
				last.get(timeouts.tts().toMillis() + 1000, TimeUnit.MILLISECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			catch (ExecutionException | TimeoutException e) {
				unavailable = true;
			}
		}

	}

}
