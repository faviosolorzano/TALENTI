package com.talenti.practice.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.talenti.practice.config.DoraProperties;
import com.talenti.practice.domain.AudioInspector;
import com.talenti.practice.domain.DoraTexts;
import com.talenti.practice.domain.InputMode;
import com.talenti.practice.domain.SessionMode;
import com.talenti.practice.domain.SessionStatus;
import com.talenti.practice.persistence.PracticeRepository;
import com.talenti.practice.persistence.PracticeRepository.QuestionRow;
import com.talenti.practice.persistence.PracticeRepository.SessionRow;
import com.talenti.practice.persistence.PracticeRepository.TurnRow;
import com.talenti.practice.port.ConversationPort;
import com.talenti.practice.port.ConversationPort.BridgeOutcome;
import com.talenti.practice.port.ConversationPort.ReplySource;
import com.talenti.practice.port.LlmPort;
import com.talenti.practice.port.SpokenAudio;
import com.talenti.profile.ProfileFirstName;
import com.talenti.shared.ai.AiUsageOutcome;
import com.talenti.shared.web.ApiException;
import com.talenti.shared.web.TooManyRequestsException;

/**
 * La conversación con DORA (012-05 a 012-14): iniciar, cada turno, corregir, reintentar la retroalimentación, terminar y
 * volver a escuchar una pregunta. Todo se valida ANTES de abrir el flujo (404, 409, 413, 415, 422, 429); el flujo
 * mismo nunca falla: degrada (texto en vez de voz, plantilla en vez de IA) y siempre termina en {@code turn.done} o
 * {@code error}.
 *
 * <p>Solo conoce {@link ConversationPort} para hablar (012-31). El audio del turno vive solo en memoria y se descarta
 * al transcribir (012-07). Nada de contenido sale a los registros (012-33).
 */
@Service
public class DoraService {

	private static final Logger log = LoggerFactory.getLogger(DoraService.class);

	/** Un turno o un inicio ya validado: falta correrlo sobre un flujo. */
	public interface Stream {

		void run(EventSink sink);

	}

	/** Lo que llega en un turno: audio (multipart) o texto. */
	public record TurnCommand(byte[] audio, String text, Integer questionPosition, UUID clientTurnId) {

		@Override
		public String toString() {
			return "TurnCommand[audio=" + (audio != null) + ", position=" + questionPosition + "]";
		}

	}

	/** Audio de una pregunta para volver a escucharla (012-10). */
	public record QuestionAudio(String mime, byte[] data) {

		@Override
		public String toString() {
			return "QuestionAudio[mime=" + mime + "]";
		}

	}

	private final PracticeRepository repository;

	private final ConversationPort conversation;

	private final LiveSessions live;

	private final SessionLifecycle lifecycle;

	private final FeedbackService feedback;

	private final SessionViews views;

	private final UsageMeter usage;

	private final ProfileFirstName names;

	private final DoraTexts texts;

	private final DoraProperties properties;

	private final Clock clock;

	private final java.util.concurrent.ExecutorService executor;

	private final com.talenti.practice.config.DoraRealtimeProperties realtime;

	/** Sin la configuración del tiempo real (pruebas unitarias del modo por turnos): respuesta armada de 8 000. */
	DoraService(PracticeRepository repository, ConversationPort conversation, LiveSessions live,
			SessionLifecycle lifecycle, FeedbackService feedback, SessionViews views, UsageMeter usage,
			ProfileFirstName names, DoraTexts texts, DoraProperties properties, Clock clock,
			java.util.concurrent.ExecutorService executor) {
		this(repository, conversation, live, lifecycle, feedback, views, usage, names, texts, properties, clock, executor,
				null);
	}

	@org.springframework.beans.factory.annotation.Autowired
	DoraService(PracticeRepository repository, ConversationPort conversation, LiveSessions live,
			SessionLifecycle lifecycle, FeedbackService feedback, SessionViews views, UsageMeter usage,
			ProfileFirstName names, DoraTexts texts, DoraProperties properties, Clock clock,
			@org.springframework.beans.factory.annotation.Qualifier("doraExecutor") java.util.concurrent.ExecutorService executor,
			com.talenti.practice.config.DoraRealtimeProperties realtime) {
		this.realtime = realtime;
		this.executor = executor;
		this.repository = repository;
		this.conversation = conversation;
		this.live = live;
		this.lifecycle = lifecycle;
		this.feedback = feedback;
		this.views = views;
		this.usage = usage;
		this.names = names;
		this.texts = texts;
		this.properties = properties;
		this.clock = clock;
	}

	// ------------------------------------------------------------------------------------------ start

	/** 012-05: saludo fijo + aviso + pregunta 1 (o la actual, si la sesión ya estaba en curso: reanudación). */
	public Stream start(UUID userId, UUID sessionId) {
		SessionRow session = owned(userId, sessionId);
		if (!session.status().isOpen()) {
			throw DoraErrors.conflict("session-closed");
		}
		rejectIfRealtimeOpen(session);
		if (!live.tryLock(sessionId)) {
			throw DoraErrors.conflict("turn-in-progress");
		}
		long started = System.nanoTime();
		return sink -> {
			try {
				boolean resumed = session.status() == SessionStatus.IN_PROGRESS;
				repository.markStarted(sessionId, clock.instant());
				lifecycle.event(session, resumed ? "RESUMED" : "STARTED", Map.of());
				List<QuestionRow> questions = repository.questions(sessionId);
				Integer position = SessionViews.nextPosition(session, repository.turns(sessionId));
				boolean voice = session.voiceEnabled() && !usage.spendReached();
				Degradation degradation = new Degradation(session, sink, started);
				String opening = texts.opening(firstName(session), session.mode());
				sink.send("reply.text", map("seq", 1, "text", opening, "source", "TEMPLATE", "generatedBy", "TEMPLATE"));
				if (voice) {
					Optional<SpokenAudio> audio = audio(session, "opening", opening);
					if (audio.isPresent()) {
						sink.send("reply.audio", audioEvent(1, audio.get()));
					}
					else {
						degradation.report("TTS_UNAVAILABLE");
					}
				}
				emitQuestion(session, questions, position, voice, sink, degradation);
				sink.send("turn.done", map("nextPosition", position, "sessionStatus", "IN_PROGRESS", "timings",
						map("totalMs", millis(started))));
			}
			catch (RuntimeException e) {
				log.warn("Falló el inicio de una sesión con DORA: {}", e.getClass().getName());
				sink.send("error", map("code", "internal-error", "retryable", true));
			}
			finally {
				repository.touch(sessionId, clock.instant());
				live.unlock(sessionId);
				sink.complete();
			}
		};
	}

	// ------------------------------------------------------------------------------------------ turno

	/**
	 * 012-06 a 012-13: valida el turno y devuelve el flujo que lo ejecuta.
	 *
	 * @param received cuándo llegó completo el pedido ({@link System#nanoTime()}): desde aquí se miden los tiempos
	 */
	public Stream turn(UUID userId, UUID sessionId, TurnCommand command, long received) {
		SessionRow session = owned(userId, sessionId);
		if (command.clientTurnId() == null) {
			throw DoraErrors.invalid("clientTurnId", "required");
		}
		if (command.questionPosition() == null) {
			throw DoraErrors.invalid("questionPosition", "required");
		}
		ConversationPort.InputAudio audio = null;
		String text = null;
		if (command.audio() != null) {
			audio = validateAudio(command.audio());
		}
		else {
			text = command.text() == null ? "" : command.text().strip();
			if (text.isEmpty()) {
				throw DoraErrors.invalid("text", "required");
			}
			if (text.length() > properties.maxTranscript()) {
				throw DoraErrors.invalid("text", "max-length", properties.maxTranscript());
			}
		}
		if (!live.allowTurn(userId)) {
			throw new TooManyRequestsException(60);
		}
		// Pocas consultas antes del flujo: todo esto cuenta para el tiempo hasta el primer audio (012-15).
		List<TurnRow> turns = repository.turns(sessionId);
		Optional<TurnRow> saved = turns.stream().filter(t -> t.clientTurnId().equals(command.clientTurnId())).findFirst();
		if (saved.isPresent()) {
			if (live.isBusy(sessionId)) {
				throw DoraErrors.conflict("turn-in-progress");
			}
			return replay(session, saved.get());
		}
		if (session.status() == SessionStatus.CREATED) {
			throw DoraErrors.conflict("session-not-started");
		}
		if (!session.status().isOpen()) {
			throw DoraErrors.conflict("session-closed");
		}
		rejectIfRealtimeOpen(session);
		if (!live.tryLock(sessionId)) {
			throw DoraErrors.conflict("turn-in-progress");
		}
		try {
			List<QuestionRow> questions = live.questions(sessionId, () -> repository.questions(sessionId));
			int position = command.questionPosition();
			int attempt = attemptFor(session, turns, position);
			QuestionRow question = questions.stream().filter(q -> q.position() == position).findFirst()
					.orElseThrow(() -> DoraErrors.conflict("question-out-of-order"));
			Turn turn = new Turn(session, questions, question, attempt, command.clientTurnId(), audio, text, received);
			return turn::run;
		}
		catch (RuntimeException e) {
			live.unlock(sessionId);
			throw e;
		}
	}

	/**
	 * Mientras haya una llamada en tiempo real abierta no se conversa por turnos en la misma sesión: primero se cuelga
	 * con {@code FALLBACK} (012-53). Solo consulta la base si la sesión usa el canal en tiempo real.
	 */
	private void rejectIfRealtimeOpen(SessionRow session) {
		if ("REALTIME".equals(session.channel()) && repository.openCall(session.id()).isPresent()) {
			throw DoraErrors.conflict("realtime-call-open");
		}
	}

	/** Intento de la respuesta (1, o 2 si repite la última en la práctica); 409 si la posición no corresponde. */
	private int attemptFor(SessionRow session, List<TurnRow> turns, int position) {
		Integer expected = SessionViews.nextPosition(session, turns);
		if (expected != null && position == expected) {
			return 1;
		}
		int lastAnswered = expected == null ? session.questionCount() : expected - 1;
		if (session.mode() == SessionMode.PRACTICE && position == lastAnswered && position >= 1) {
			long attempts = turns.stream().filter(t -> t.position() == position).count();
			if (attempts <= properties.retryPerQuestion()) {
				return (int) attempts + 1;
			}
			throw DoraErrors.conflict("retry-limit-reached");
		}
		throw DoraErrors.conflict("question-out-of-order");
	}

	private ConversationPort.InputAudio validateAudio(byte[] audio) {
		if (audio.length == 0) {
			throw DoraErrors.invalid("audio", "required");
		}
		if (audio.length > properties.maxAudioBytes().toBytes()) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "audio-too-large");
		}
		AudioInspector.Format format = AudioInspector.detect(audio)
				.orElseThrow(() -> new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "audio-unsupported"));
		Integer durationMs = AudioInspector.durationMs(audio, format).orElse(null);
		if (durationMs != null && durationMs > properties.maxAudio().toMillis()) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "audio-too-long");
		}
		return new ConversationPort.InputAudio(audio, format.mime(), durationMs);
	}

	/** Un turno en curso: escucha al puerto y lo vuelca en el flujo, en orden. */
	private final class Turn implements ConversationPort.TurnListener {

		private final SessionRow session;

		private final List<QuestionRow> questions;

		private final QuestionRow question;

		private final int attempt;

		private final UUID clientTurnId;

		private ConversationPort.InputAudio audio;

		private final String text;

		private final long received;

		private final UUID turnId = UUID.randomUUID();

		private EventSink sink;

		private Degradation degradation;

		private volatile boolean saved;

		private volatile String errorCode;

		private volatile long firstAudioNanos;

		private volatile Long sttMs;

		private volatile BridgeOutcome outcome;

		private volatile CompletableFuture<Void> persisted;

		/** Espera la escritura de la respuesta; si falló (por ejemplo, el clientTurnId ya existía), avisa y corta. */
		private boolean awaitPersisted() {
			try {
				persisted.join();
				return true;
			}
			catch (java.util.concurrent.CompletionException failure) {
				boolean duplicate = failure.getCause() instanceof DuplicateKeyException;
				log.warn("No se pudo guardar una respuesta: {}", failure.getCause() == null ? "?"
						: failure.getCause().getClass().getName());
				sink.send("error", map("code", duplicate ? "turn-id-conflict" : "internal-error", "retryable",
						!duplicate, "atMs", millis(received)));
				return false;
			}
		}

		Turn(SessionRow session, List<QuestionRow> questions, QuestionRow question, int attempt, UUID clientTurnId,
				ConversationPort.InputAudio audio, String text, long received) {
			this.session = session;
			this.questions = questions;
			this.question = question;
			this.attempt = attempt;
			this.clientTurnId = clientTurnId;
			this.audio = audio;
			this.text = text;
			this.received = received;
		}

		void run(EventSink sink) {
			this.sink = sink;
			this.degradation = new Degradation(session, sink, received);
			try {
				boolean aiAllowed = !usage.spendReached();
				boolean voice = session.voiceEnabled() && aiAllowed;
				if (!aiAllowed) {
					degradation.report("AI_LIMIT");
				}
				if (audio != null && !aiAllowed) {
					// Sin gasto disponible no hay voz a texto: la persona puede escribir (RF-40).
					audio = null;
					sink.send("error", map("code", "stt-failed", "retryable", true));
					return;
				}
				Integer next = question.position() < session.questionCount() ? question.position() + 1 : null;
				QuestionRow nextQuestion = next == null ? null
						: questions.stream().filter(q -> q.position() == next).findFirst().orElse(null);
				String firstName = firstName(session);
				LlmPort.Purpose purpose = session.mode() == SessionMode.INTERVIEW ? LlmPort.Purpose.INTERVIEW
						: LlmPort.Purpose.PRACTICE;
				LlmPort.BridgeRequest bridge = new LlmPort.BridgeRequest(purpose, firstName, question.text(),
						nextQuestion == null ? null : nextQuestion.text(), properties.bridgeMaxTokens());
				ConversationPort.TurnInput input = new ConversationPort.TurnInput(audio, text, bridge,
						texts.templateBridge(question.position(), next == null), voice, aiAllowed,
						usage.callLog(session.userId()), received);
				// El audio solo vive en memoria: tras entregarlo al puerto se suelta la referencia (012-07).
				audio = null;
				conversation.runTurn(input, this);
				if (errorCode != null || !saved) {
					if (persisted != null) {
						awaitPersisted();
					}
					if ("stt-failed".equals(errorCode)) {
						repository.addDegraded(session.id(), "STT_FAILED");
					}
					return;
				}
				if (!awaitPersisted()) {
					return;
				}
				emitQuestion(session, questions, next, voice, sink, degradation);
				Integer firstAudioMs = firstAudioNanos == 0 ? null : (int) ((firstAudioNanos - received) / 1_000_000);
				int totalMs = (int) millis(received);
				repository.updateTurnTimings(turnId, firstAudioMs, totalMs,
						outcome == null ? null : outcome.source().name());
				SessionStatus status = SessionStatus.IN_PROGRESS;
				if (next == null && session.mode() == SessionMode.INTERVIEW) {
					lifecycle.close(session, SessionStatus.COMPLETED);
					status = SessionStatus.COMPLETED;
				}
				Map<String, Object> timings = map("sttMs", sttMs, "firstAudioMs", firstAudioMs, "totalMs", totalMs);
				if (outcome != null && outcome.llmDoneNanos() > 0) {
					timings.put("llmDoneMs", (outcome.llmDoneNanos() - received) / 1_000_000);
				}
				sink.send("turn.done", map("turnId", turnId, "nextPosition", next, "sessionStatus", status.name(),
						"timings", timings));
				if (sink.disconnected()) {
					lifecycle.event(session, "DISCONNECTED", map("position", question.position()));
				}
			}
			catch (RuntimeException e) {
				log.warn("Falló un turno con DORA: {}", e.getClass().getName());
				sink.send("error", map("code", "internal-error", "retryable", true));
			}
			finally {
				audio = null;
				repository.touch(session.id(), clock.instant());
				live.unlock(session.id());
				sink.complete();
			}
		}

		@Override
		public boolean onTranscript(String transcript, Long sttMs, Integer audioMs) {
			String value = transcript.length() > properties.maxTranscript()
					? transcript.substring(0, properties.maxTranscript()) : transcript;
			InputMode mode = text == null ? InputMode.VOICE : InputMode.TEXT;
			saved = true;
			this.sttMs = sttMs;
			// Se guarda en paralelo con la frase puente (012-15): la escritura no suma tiempo hasta el primer audio. El
			// turno espera a que termine antes de cerrar, así que si la red se corta, la respuesta queda guardada.
			Instant now = clock.instant();
			persisted = CompletableFuture.runAsync(() -> repository.insertTurn(turnId, question.id(), clientTurnId,
					attempt, mode, value, audioMs, sttMs == null ? null : sttMs.intValue(), now), executor);
			sink.send("transcript", map("turnId", turnId, "text", value, "inputMode", mode.name()));
			// Lo demás va fuera del camino de la voz (012-15).
			persisted.thenRunAsync(() -> {
				try {
					if (attempt == 1 && question.position() == 1) {
						usage.sessionUsed(session.id(), session.userId(), session.mode(), AiUsageOutcome.SUCCESS);
					}
					lifecycle.event(session, "ANSWERED", map("position", question.position(), "inputMode",
							mode.name(), "attempt", attempt));
					if (session.mode() == SessionMode.PRACTICE) {
						feedback.schedule(session.id(), turnId, session.userId(), SessionMode.PRACTICE);
					}
				}
				catch (RuntimeException e) {
					log.warn("No se pudo anotar lo posterior a una respuesta: {}", e.getClass().getName());
				}
			}, executor);
			return true;
		}

		@Override
		public void onReplyText(int seq, String reply, ReplySource source) {
			sink.send("reply.text", map("seq", seq, "text", reply, "source", source.name(), "generatedBy", source.name(),
					"atMs", millis(received)));
		}

		@Override
		public void onReplyAudio(int seq, SpokenAudio spoken) {
			if (firstAudioNanos == 0) {
				firstAudioNanos = System.nanoTime();
			}
			sink.send("reply.audio", audioEvent(seq, spoken));
		}

		@Override
		public void onDegraded(String reason) {
			degradation.report(reason);
		}

		@Override
		public void onError(String code, boolean retryable) {
			errorCode = code;
			sink.send("error", map("code", code, "retryable", retryable, "atMs", millis(received)));
		}

		@Override
		public void onDone(BridgeOutcome done) {
			outcome = done;
		}

	}

	/** Mismo {@code clientTurnId}: lo guardado, sin volver a llamar a la IA ni a la voz (012-06). */
	private Stream replay(SessionRow session, TurnRow turn) {
		return sink -> {
			try {
				sink.send("transcript", map("turnId", turn.id(), "text", turn.transcriptFinal(), "inputMode",
						turn.inputMode().name(), "replayed", true));
				List<QuestionRow> questions = repository.questions(session.id());
				Integer next = turn.position() < session.questionCount() ? turn.position() + 1 : null;
				Map<String, Object> question = questionEvent(session, questions, next);
				if (session.voiceEnabled()) {
					String key = next == null ? "closing" : "q:" + next;
					live.audio(session.id(), key).filter(CompletableFuture::isDone).flatMap(f -> f.getNow(Optional.empty()))
							.ifPresent(audio -> question.put("audio", audioData(audio)));
				}
				sink.send("question", question);
				SessionStatus status = repository.findById(session.id()).map(SessionRow::status)
						.orElse(SessionStatus.COMPLETED);
				sink.send("turn.done", map("turnId", turn.id(), "nextPosition", next, "sessionStatus", status.name(),
						"replayed", true, "timings", map("sttMs", turn.sttMs(), "firstAudioMs", turn.firstAudioMs(),
								"totalMs", turn.totalMs())));
			}
			finally {
				sink.complete();
			}
		};
	}

	// ------------------------------------------------------------------------------------------ preguntas

	/** Emite la pregunta {@code position} (o el cierre si es nula) con su audio ya preparado, y prepara la siguiente. */
	private void emitQuestion(SessionRow session, List<QuestionRow> questions, Integer position, boolean voice,
			EventSink sink, Degradation degradation) {
		Map<String, Object> event = questionEvent(session, questions, position);
		if (voice) {
			String key = position == null ? "closing" : "q:" + position;
			Optional<SpokenAudio> audio = audio(session, key, (String) event.get("text"));
			if (audio.isPresent()) {
				event.put("audio", audioData(audio.get()));
			}
			else {
				degradation.report("TTS_UNAVAILABLE");
			}
			// 012-10: mientras la persona responde, se prepara el audio de lo que DORA dirá después.
			if (position != null) {
				prefetchQuestion(session, questions, position + 1);
			}
		}
		sink.send("question", event);
	}

	private Map<String, Object> questionEvent(SessionRow session, List<QuestionRow> questions, Integer position) {
		if (position == null) {
			return map("position", null, "text", texts.closing(session.mode()), "source", "TEMPLATE", "generatedBy",
					"TEMPLATE", "closing", true, "audio", null);
		}
		QuestionRow question = questions.stream().filter(q -> q.position() == position).findFirst().orElseThrow();
		return map("position", position, "text", question.text(), "source", question.source().name(), "generatedBy",
				question.source().name(), "closing", false, "audio", null);
	}

	/** Prepara (en memoria) el audio de la pregunta {@code position}, o del cierre si ya no hay más. */
	public void prefetchQuestion(SessionRow session, List<QuestionRow> questions, int position) {
		if (!session.voiceEnabled() || usage.spendReached()) {
			return;
		}
		if (position > session.questionCount()) {
			String closing = texts.closing(session.mode());
			live.prefetch(session.id(), "closing", () -> conversation.speak(closing, usage.callLog(session.userId())));
			return;
		}
		questions.stream().filter(q -> q.position() == position).findFirst()
				.ifPresent(q -> live.prefetch(session.id(), "q:" + position,
						() -> conversation.speak(q.text(), usage.callLog(session.userId()))));
	}

	/** Prepara el saludo con el nombre (si lo hay) para que el inicio sea inmediato. */
	public void prefetchOpening(SessionRow session) {
		if (!session.voiceEnabled() || usage.spendReached()) {
			return;
		}
		String opening = texts.opening(firstName(session), session.mode());
		live.prefetch(session.id(), "opening", () -> conversation.speak(opening, usage.callLog(session.userId())));
	}

	/** El audio ya preparado (o lo prepara ahora); espera como mucho {@code tts-timeout}. */
	private Optional<SpokenAudio> audio(SessionRow session, String key, String text) {
		CompletableFuture<Optional<SpokenAudio>> future = live.audio(session.id(), key)
				.orElseGet(() -> live.prefetch(session.id(), key,
						() -> conversation.speak(text, usage.callLog(session.userId()))));
		try {
			return future.get(properties.ttsTimeout().toMillis() + 500, TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
		catch (ExecutionException | TimeoutException e) {
			return Optional.empty();
		}
	}

	/** 012-10: volver a escuchar una pregunta ya hecha (desde la memoria o sintetizándola de nuevo). */
	public QuestionAudio questionAudio(UUID userId, UUID sessionId, int position) {
		SessionRow session = owned(userId, sessionId);
		List<TurnRow> turns = repository.turns(sessionId);
		Integer next = SessionViews.nextPosition(session, turns);
		int asked = session.status() == SessionStatus.CREATED ? 0 : next == null ? session.questionCount() : next;
		QuestionRow question = repository.questions(sessionId).stream().filter(q -> q.position() == position)
				.filter(q -> q.position() <= asked).findFirst()
				.orElseThrow(() -> DoraErrors.notFound("question-not-found"));
		if (usage.spendReached()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "voice-unavailable");
		}
		String key = "q:" + position;
		Optional<SpokenAudio> cached = live.audio(sessionId, key).filter(CompletableFuture::isDone)
				.flatMap(f -> f.getNow(Optional.empty()));
		Optional<SpokenAudio> audio = cached.isPresent() ? cached
				: conversation.speak(question.text(), usage.callLog(userId));
		SpokenAudio spoken = audio.orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "voice-unavailable"));
		return new QuestionAudio(spoken.mime(), spoken.data());
	}

	// ------------------------------------------------------------------------------------------ corregir, reintentar, terminar

	/** 012-13: corregir la transcripción (se conserva la original) y recalcular la retroalimentación. */
	public Views.Turn correct(UUID userId, UUID sessionId, UUID turnId, String transcript) {
		SessionRow session = owned(userId, sessionId);
		TurnRow turn = repository.findTurn(turnId, sessionId).orElseThrow(() -> DoraErrors.notFound("turn-not-found"));
		String value = transcript == null ? "" : transcript.strip();
		if (value.isEmpty()) {
			throw DoraErrors.invalid("transcript", "required");
		}
		// Una respuesta armada en tiempo real (respuesta + repregunta) admite hasta max-answer (duda E2-11).
		int max = repository.hasRealtimeCalls(sessionId) ? Math.max(properties.maxTranscript(), realtime == null ? 8000 : realtime.maxAnswer())
				: properties.maxTranscript();
		if (value.length() > max) {
			throw DoraErrors.invalid("transcript", "max-length", max);
		}
		if (session.mode() == SessionMode.INTERVIEW && !session.status().isOpen()
				&& session.status() != SessionStatus.COMPLETED) {
			throw DoraErrors.conflict("session-closed");
		}
		repository.correctTranscript(turnId, value, clock.instant());
		lifecycle.event(session, "TRANSCRIPT_CORRECTED", map("position", turn.position(), "attempt", turn.attempt()));
		String feedbackStatus = null;
		if (session.mode() == SessionMode.PRACTICE || session.status() == SessionStatus.COMPLETED) {
			feedback.schedule(sessionId, turnId, userId, session.mode());
			if (session.mode() == SessionMode.PRACTICE) {
				feedbackStatus = "PENDING";
			}
		}
		if (session.status().isOpen()) {
			repository.touch(sessionId, clock.instant());
		}
		return new Views.Turn(turnId, turn.position(), turn.attempt(), turn.inputMode().name(), turn.transcriptRaw(),
				value, true, feedbackStatus);
	}

	/** 012-19: reintentar una retroalimentación {@code UNAVAILABLE}. */
	public void retryFeedback(UUID userId, UUID sessionId, UUID turnId) {
		SessionRow session = owned(userId, sessionId);
		repository.findTurn(turnId, sessionId).orElseThrow(() -> DoraErrors.notFound("turn-not-found"));
		if (session.mode() != SessionMode.PRACTICE) {
			throw DoraErrors.conflict("feedback-not-retryable");
		}
		if (!feedback.retry(sessionId, turnId, userId, SessionMode.PRACTICE)) {
			throw DoraErrors.conflict("feedback-not-retryable");
		}
	}

	/** 012-14: la persona termina la sesión. Si ya estaba cerrada, la devuelve tal cual. */
	public Views.Session finish(UUID userId, UUID sessionId) {
		SessionRow session = owned(userId, sessionId);
		if (session.status().isOpen()) {
			if (live.isBusy(sessionId)) {
				throw DoraErrors.conflict("turn-in-progress");
			}
			lifecycle.close(session, SessionStatus.COMPLETED);
		}
		SessionRow current = repository.findById(sessionId).orElseThrow(DoraErrors::sessionNotFound);
		return views.session(current, current.mode() == SessionMode.PRACTICE);
	}

	/** 012-15: p50 y p95 del tiempo hasta el primer audio de los últimos {@code last} turnos. Solo números. */
	public Views.Latency latency(int last) {
		List<Integer> values = repository.recentFirstAudioMs(last);
		return new Views.Latency(com.talenti.practice.domain.Percentiles.of(values, 50),
				com.talenti.practice.domain.Percentiles.of(values, 95), values.size());
	}

	/** El primer nombre de la persona, leído una vez por sesión (la sesión usa lo que había al empezar). */
	private String firstName(SessionRow session) {
		return live.firstName(session.id(), () -> names.firstName(session.userId()).orElse(null));
	}

	/** Olvida el audio en memoria de una sesión borrada. */
	public void forget(UUID sessionId) {
		live.forget(sessionId);
	}

	// ------------------------------------------------------------------------------------------ piezas

	private SessionRow owned(UUID userId, UUID sessionId) {
		return repository.findOwned(sessionId, userId).orElseThrow(DoraErrors::sessionNotFound);
	}

	/** Avisa una sola vez por flujo cada motivo de degradación y lo anota en la sesión (para 012-34). */
	private final class Degradation {

		private final SessionRow session;

		private final EventSink sink;

		private final java.util.Set<String> reported = java.util.concurrent.ConcurrentHashMap.newKeySet();

		private final long origin;

		Degradation(SessionRow session, EventSink sink, long origin) {
			this.session = session;
			this.sink = sink;
			this.origin = origin;
		}

		void report(String reason) {
			if (reported.add(reason)) {
				sink.send("degraded", map("reason", reason, "atMs", millis(origin)));
				try {
					repository.addDegraded(session.id(), reason);
				}
				catch (RuntimeException e) {
					log.warn("No se pudo anotar una degradación: {}", e.getClass().getName());
				}
			}
		}

	}

	private static Map<String, Object> audioEvent(int seq, SpokenAudio audio) {
		Map<String, Object> event = map("seq", seq);
		event.putAll(audioData(audio));
		return event;
	}

	private static Map<String, Object> audioData(SpokenAudio audio) {
		return map("mime", audio.mime(), "data", Base64.getEncoder().encodeToString(audio.data()));
	}

	private static long millis(long since) {
		return (System.nanoTime() - since) / 1_000_000;
	}

	/** Mapa ordenado que admite nulos: {@code map("a", 1, "b", null)}. */
	static Map<String, Object> map(Object... keysAndValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			map.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}

}
