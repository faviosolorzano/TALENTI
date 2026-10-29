package com.talenti.practice.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.talenti.practice.config.DoraAiProperties;
import com.talenti.practice.config.DoraRealtimeProperties;
import com.talenti.practice.domain.CallBudget;
import com.talenti.practice.domain.RealtimeInstructions;
import com.talenti.practice.domain.RealtimePhase;
import com.talenti.practice.domain.SdpOffer;
import com.talenti.practice.domain.SessionMode;
import com.talenti.practice.domain.SessionStatus;
import com.talenti.practice.domain.StageMachine;
import com.talenti.practice.persistence.PracticeRepository;
import com.talenti.practice.persistence.PracticeRepository.CallRow;
import com.talenti.practice.persistence.PracticeRepository.NewUtterance;
import com.talenti.practice.persistence.PracticeRepository.QuestionRow;
import com.talenti.practice.persistence.PracticeRepository.SessionRow;
import com.talenti.practice.persistence.PracticeRepository.UtteranceRow;
import com.talenti.practice.port.RealtimeCallPort;
import com.talenti.profile.ProfileFirstName;
import com.talenti.shared.ai.AiUsageOutcome;
import com.talenti.shared.ai.AiUsageQuota;
import com.talenti.shared.web.ApiException;
import com.talenti.shared.web.TooManyRequestsException;

/**
 * DORA en tiempo real (spec 012, enmienda 2): disponibilidad (012-38), abrir la llamada con la oferta SDP del
 * navegador (012-39 a 012-43, 012-49, 012-52), recibir lo dicho y las etapas (012-44 a 012-48), colgar con respaldo por
 * turnos (012-53) y cerrar por tiempo con el reloj del servidor (012-49). Solo conoce {@link RealtimeCallPort}: el
 * dominio no conoce al proveedor. La clave nunca sale del servidor y el SDP no se guarda ni se registra.
 */
@Service
public class RealtimeService {

	private static final Logger log = LoggerFactory.getLogger(RealtimeService.class);

	private static final int MAX_EVENT_ID = 100;

	/** Lo que llega del navegador por cada evento (012-44); los dos tipos comparten la forma. */
	public record EventInput(String type, String eventId, String speaker, String text, String inputMode,
			String occurredAt, Integer latencyMs, String phase, Integer position) {

		@Override
		public String toString() {
			return "EventInput[type=" + type + ", phase=" + phase + "]";
		}

	}

	private final PracticeRepository repository;

	private final RealtimeCallPort port;

	private final RealtimeCalls calls;

	private final SessionLifecycle lifecycle;

	private final SessionViews views;

	private final UsageMeter usage;

	private final LiveSessions live;

	private final ProfileFirstName names;

	private final RealtimeInstructions instructions;

	private final DoraRealtimeProperties properties;

	private final DoraAiProperties ai;

	private final AiUsageQuota quota;

	private final ExecutorService executor;

	private final TransactionTemplate transactions;

	private final Clock clock;

	private final RateLimiter connects;

	private final RateLimiter eventPosts;

	RealtimeService(PracticeRepository repository, RealtimeCallPort port, RealtimeCalls calls, SessionLifecycle lifecycle,
			SessionViews views, UsageMeter usage, LiveSessions live, ProfileFirstName names,
			RealtimeInstructions instructions, DoraRealtimeProperties properties, DoraAiProperties ai,
			AiUsageQuota quota, @Qualifier("doraExecutor") ExecutorService executor, TransactionTemplate transactions,
			Clock clock) {
		this.repository = repository;
		this.port = port;
		this.calls = calls;
		this.lifecycle = lifecycle;
		this.views = views;
		this.usage = usage;
		this.live = live;
		this.names = names;
		this.instructions = instructions;
		this.properties = properties;
		this.ai = ai;
		this.quota = quota;
		this.executor = executor;
		this.transactions = transactions;
		this.clock = clock;
		this.connects = new RateLimiter(properties.connectsPerMinute());
		this.eventPosts = new RateLimiter(properties.eventsPerMinute());
	}

	// ------------------------------------------------------------------------------------------ disponibilidad

	/** 012-38: proveedor y disponibilidad del tiempo real. No llama a OpenAI. */
	public Views.Dora availability() {
		String provider = ai.enabled() ? "OPENAI" : "SIMULATED";
		return new Views.Dora(provider, realtime(null));
	}

	private Views.Realtime realtime(SessionRow session) {
		boolean simulatedPort = port.info().adapter().endsWith("/simulated");
		if (simulatedPort && !properties.simulated()) {
			return new Views.Realtime(false, properties.enabled() ? "NOT_CONFIGURED" : "DISABLED");
		}
		if (!simulatedPort && !properties.enabled()) {
			return new Views.Realtime(false, "DISABLED");
		}
		Duration max = session != null && session.mode() == SessionMode.INTERVIEW ? properties.maxDuration().interview()
				: properties.maxDuration().practice();
		CallBudget.Plan plan = budget(session, max);
		return plan.affordable() ? new Views.Realtime(true, null) : new Views.Realtime(false, "SPEND_LIMIT");
	}

	/** Lo que alcanza con el gasto restante, descontado lo reservado por las otras llamadas abiertas (012-49). */
	private CallBudget.Plan budget(SessionRow session, Duration max) {
		// Al milisegundo: lo que se guarda en la base y lo que se responde coinciden (la base guarda microsegundos).
		Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
		long remaining;
		try {
			remaining = quota.monthlySpendRemainingMicros();
		}
		catch (RuntimeException e) {
			log.warn("No se pudo consultar el gasto restante ({}); se trata como agotado", e.getClass().getName());
			remaining = 0;
		}
		long reserved = 0;
		for (Instant end : repository.openCallEnds(session == null ? new UUID(0, 0) : session.id())) {
			long seconds = Math.max(0, Duration.between(now, end.plus(properties.grace())).toSeconds());
			reserved += CallBudget.costMicros(seconds, properties.pricePerMinute());
		}
		return CallBudget.plan(now, max, remaining - reserved, properties.pricePerMinute(),
				Duration.ofMinutes(properties.minMinutes()), properties.warnBefore(),
				session == null ? null : session.endsAt());
	}

	// ------------------------------------------------------------------------------------------ conectar

	/** 012-39 a 012-43, 012-49, 012-52: abre la llamada. {@code replacesCallId}: la llamada que se cortó (reconexión). */
	public Views.RealtimeCall connect(UUID userId, UUID sessionId, String sdp, UUID replacesCallId) {
		SessionRow session = owned(userId, sessionId);
		switch (SdpOffer.check(sdp, properties.maxSdp().toBytes())) {
			case TOO_LARGE -> throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "sdp-too-large");
			case INVALID -> throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "sdp-invalid");
			default -> {
			}
		}
		if (!connects.allow(userId)) {
			throw new TooManyRequestsException(60);
		}
		if (!session.status().isOpen()) {
			throw DoraErrors.conflict("session-closed");
		}
		if (pastEnd(session)) {
			closeByTime(session);
			throw DoraErrors.conflict("session-closed");
		}
		Views.Realtime available = realtime(session);
		if (!available.available()) {
			throw DoraErrors.realtimeUnavailable(available.reason());
		}
		Optional<CallRow> open = repository.openCall(sessionId);
		if (open.isPresent()) {
			if (replacesCallId == null || !replacesCallId.equals(open.get().id())) {
				throw DoraErrors.conflict("realtime-call-open");
			}
			// La llamada anterior se cortó sin colgar: se cierra y se reconecta.
			if (calls.close(session, open.get(), "REPLACED", false)) {
				calls.event(session, "DISCONNECTED", Map.of("channel", "REALTIME"));
			}
		}
		int opened = repository.countOpenedCalls(sessionId);
		if (opened > properties.maxReconnects()) {
			throw DoraErrors.conflict("realtime-reconnects-exhausted");
		}
		if (live.isBusy(sessionId)) {
			throw DoraErrors.conflict("turn-in-progress");
		}
		Duration max = session.mode() == SessionMode.INTERVIEW ? properties.maxDuration().interview()
				: properties.maxDuration().practice();
		CallBudget.Plan plan = budget(session, max);
		if (!plan.affordable()) {
			throw DoraErrors.realtimeUnavailable("SPEND_LIMIT");
		}
		List<QuestionRow> questions = repository.questions(sessionId);
		Integer startPosition = SessionViews.nextPosition(session, repository.turns(sessionId));
		boolean resume = opened > 0 || session.status() == SessionStatus.IN_PROGRESS;
		RealtimeInstructions.Start start = resume ? RealtimeInstructions.Start.RESUME
				: RealtimeInstructions.Start.GREETING;
		String text = instructions.build(new RealtimeInstructions.Request(session.mode(), firstName(session),
				questions.stream().map(q -> new RealtimeInstructions.PlannedQuestion(q.position(), q.text())).toList(),
				start, startPosition, properties.followUpsPerQuestion()));
		UUID callId = UUID.randomUUID();
		try {
			repository.insertCall(callId, sessionId, instructions.version(), start.name(), clock.instant());
		}
		catch (DuplicateKeyException other) {
			throw DoraErrors.conflict("realtime-call-open");
		}
		RealtimeCallPort.CreatedCall created;
		Future<RealtimeCallPort.CreatedCall> future = executor
				.submit(() -> port.createCall(new RealtimeCallPort.CallRequest(sdp, text)));
		try {
			created = future.get(properties.connectTimeout().toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			future.cancel(true);
			repository.callFailed(callId, clock.instant());
			throw DoraErrors.realtimeUnavailable(null);
		}
		catch (ExecutionException | TimeoutException | RuntimeException e) {
			future.cancel(true);
			repository.callFailed(callId, clock.instant());
			log.warn("No se pudo abrir una llamada en tiempo real: {}",
					e instanceof ExecutionException && e.getCause() != null ? e.getCause().getClass().getName()
							: e.getClass().getName());
			throw DoraErrors.realtimeUnavailable(null);
		}
		Instant now = clock.instant();
		repository.callOpened(callId, created.providerCallId(), now);
		RealtimeCallPort.Info info = port.info();
		repository.markRealtime(sessionId, info.model(), info.voice(), info.transcriptionModel(), instructions.version(),
				plan.endsAt(), now);
		calls.event(session, resume ? "RESUMED" : "STARTED", Map.of("channel", "REALTIME"));
		// 012-50: el cupo de práctica se anota al abrir con éxito la primera llamada (idempotente por la sesión).
		usage.sessionUsed(sessionId, userId, session.mode(), AiUsageOutcome.SUCCESS);
		RealtimeCallPort.EventNames names = port.events();
		return new Views.RealtimeCall(callId, created.sdp(), plan.endsAt(), plan.warnAt(), start.name(), startPosition,
				new Views.RealtimeEvents(names.channel(), names.personTranscript(), names.doraTranscript(),
						names.doraTranscriptDelta(), names.speechStarted(), names.speechStopped(),
						names.outputAudioStarted(), names.outputAudioStopped(), names.functionCall(),
						names.responseDone(), names.itemCreate(), names.responseCreate()));
	}

	// ------------------------------------------------------------------------------------------ eventos

	/** 012-44 a 012-48: guarda lo dicho y las etapas, idempotente por {@code eventId}; arma las respuestas. */
	public Views.EventsResult events(UUID userId, UUID sessionId, UUID callId, List<EventInput> events) {
		SessionRow session = owned(userId, sessionId);
		if (callId == null) {
			throw DoraErrors.invalid("callId", "required");
		}
		if (events == null) {
			throw DoraErrors.invalid("events", "required");
		}
		if (events.size() > properties.maxEventsPerRequest()) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "too-many-events");
		}
		if (!eventPosts.allow(userId)) {
			throw new TooManyRequestsException(60);
		}
		if (!session.status().isOpen()) {
			throw DoraErrors.conflict("session-closed");
		}
		CallRow call = repository.findCall(callId, sessionId).filter(c -> "OPEN".equals(c.status()))
				.orElseThrow(() -> DoraErrors.conflict("session-closed"));
		if (pastEnd(session)) {
			closeByTime(session);
			throw DoraErrors.conflict("session-closed");
		}
		int[] counts = new int[3];
		List<RealtimeCalls.Assembled> assembled = transactions.execute(tx -> {
			repository.lockSession(sessionId);
			return process(session, call, events, counts);
		});
		repository.touch(sessionId, clock.instant());
		calls.afterAssembled(session, assembled == null ? List.of() : assembled);
		return new Views.EventsResult(counts[0], counts[1], counts[2]);
	}

	private List<RealtimeCalls.Assembled> process(SessionRow session, CallRow call, List<EventInput> events,
			int[] counts) {
		List<UtteranceRow> rows = new ArrayList<>(repository.utterances(session.id()));
		java.util.Set<String> seen = new java.util.HashSet<>();
		long seq = 0;
		for (UtteranceRow row : rows) {
			seen.add(row.eventId());
			seq = Math.max(seq, row.seq());
		}
		RealtimeCalls.Conversation state = RealtimeCalls.conversation(rows);
		java.util.Set<Integer> plan = new java.util.HashSet<>();
		repository.questions(session.id()).forEach(q -> plan.add(q.position()));
		StageMachine machine = new StageMachine(plan, properties.followUpsPerQuestion());
		TreeSet<Integer> toAssemble = new TreeSet<>();
		Instant now = clock.instant();
		for (EventInput event : events) {
			String eventId = event == null || event.eventId() == null ? null : event.eventId().strip();
			if (eventId == null || eventId.isEmpty() || eventId.length() > MAX_EVENT_ID) {
				counts[2]++;
				continue;
			}
			if (seen.contains(eventId)) {
				counts[1]++;
				continue;
			}
			NewUtterance row = "STAGE".equals(kind(event)) ? stage(event, eventId, state, machine)
					: utterance(event, eventId, state, now);
			if (row == null) {
				counts[2]++;
				continue;
			}
			if (!repository.insertUtterance(session.id(), call.id(), row, now)) {
				counts[1]++;
				seen.add(eventId);
				continue;
			}
			counts[0]++;
			seen.add(eventId);
			if ("STAGE".equals(row.kind())) {
				Integer left = state.currentQuestion();
				if (left != null && !(RealtimePhase.valueOf(row.phase()).isQuestion()
						&& left.equals(row.questionPosition()))) {
					toAssemble.add(left); // la conversación deja la pregunta: se arma su respuesta (012-46)
				}
				if (RealtimePhase.FOLLOW_UP.name().equals(row.phase())) {
					calls.event(session, "FOLLOW_UP_ASKED", Map.of("position", row.questionPosition()));
				}
			}
			else if ("PERSON".equals(row.speaker()) && row.questionPosition() != null
					&& RealtimePhase.valueOf(row.phase()).isQuestion()
					&& !row.questionPosition().equals(state.currentQuestion())) {
				toAssemble.add(row.questionPosition()); // intervención tardía de una pregunta ya dejada: se rearma
			}
			rows.add(new UtteranceRow(null, call.id(), ++seq, eventId, row.kind(), row.speaker(), row.phase(),
					row.questionPosition(), row.text(), row.inputMode(), row.latencyMs(), row.occurredAt(), now));
			state = RealtimeCalls.conversation(rows);
		}
		return calls.assemble(session, toAssemble, rows);
	}

	private static String kind(EventInput event) {
		String type = event.type() == null ? "" : event.type().strip().toUpperCase(Locale.ROOT);
		if (type.equals("STAGE") || type.equals("UTTERANCE")) {
			return type;
		}
		return event.phase() != null && event.text() == null && event.speaker() == null ? "STAGE" : "UTTERANCE";
	}

	/** Un avance de etapa; nulo si se rechaza (hacia atrás, fuera del plan o segunda repregunta, 012-45). */
	private NewUtterance stage(EventInput event, String eventId, RealtimeCalls.Conversation state,
			StageMachine machine) {
		RealtimePhase phase = enumValue(RealtimePhase.class, event.phase());
		if (phase == null) {
			return null;
		}
		Integer position = phase.isQuestion() ? event.position() : null;
		StageMachine.Stage next = new StageMachine.Stage(phase, position);
		if (!machine.accepts(state.current(), next, state.lastQuestion(), state.followUps())) {
			return null;
		}
		Instant occurredAt = event.occurredAt() == null ? null : instant(event.occurredAt());
		return new NewUtterance(eventId, "STAGE", null, phase.name(), position, null, null, null, occurredAt);
	}

	/** Algo dicho o escrito, en la etapa vigente (o en la anterior si ocurrió antes del cambio de etapa). */
	private NewUtterance utterance(EventInput event, String eventId, RealtimeCalls.Conversation state, Instant now) {
		String speaker = event.speaker() == null ? null : event.speaker().strip().toUpperCase(Locale.ROOT);
		if (!"PERSON".equals(speaker) && !"DORA".equals(speaker)) {
			return null;
		}
		String text = event.text() == null ? "" : event.text().strip();
		if (text.isEmpty() || text.length() > properties.maxText()) {
			return null;
		}
		String mode = event.inputMode() == null ? "VOICE" : event.inputMode().strip().toUpperCase(Locale.ROOT);
		if (!mode.equals("VOICE") && !mode.equals("TEXT")) {
			return null;
		}
		Instant occurredAt = event.occurredAt() == null ? now : instant(event.occurredAt());
		if (occurredAt == null) {
			return null;
		}
		Integer latency = event.latencyMs() != null && event.latencyMs() >= 0 && event.latencyMs() <= 600_000
				&& "DORA".equals(speaker) ? event.latencyMs() : null;
		StageMachine.Stage stage = effectiveStage(state, occurredAt);
		RealtimePhase phase = stage == null || stage.phase() == null ? RealtimePhase.GREETING : stage.phase();
		return new NewUtterance(eventId, "UTTERANCE", speaker, phase.name(), phase.isQuestion() ? stage.position() : null,
				text, mode, latency, occurredAt);
	}

	/**
	 * La etapa en que ocurrió lo dicho: la vigente, salvo que lo dicho haya ocurrido antes del cambio de etapa que
	 * informó el navegador (una transcripción que llega tarde, 012-46).
	 */
	static StageMachine.Stage effectiveStage(RealtimeCalls.Conversation state, Instant occurredAt) {
		List<UtteranceRow> stages = state.stages();
		for (int i = stages.size() - 1; i >= 0; i--) {
			UtteranceRow stage = stages.get(i);
			if (stage.occurredAt() == null || !occurredAt.isBefore(stage.occurredAt())) {
				return new StageMachine.Stage(RealtimePhase.valueOf(stage.phase()), stage.questionPosition());
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------ colgar

	/** 012-53: cierra la llamada; con {@code FALLBACK} la sesión sigue por turnos desde {@code nextPosition}. */
	public Views.Hangup hangup(UUID userId, UUID sessionId, UUID callId, String rawReason) {
		SessionRow session = owned(userId, sessionId);
		if (callId == null) {
			throw DoraErrors.invalid("callId", "required");
		}
		String reason = rawReason == null ? null : rawReason.strip().toUpperCase(Locale.ROOT);
		if (reason == null || !List.of("FINISHED", "FALLBACK", "USER_LEFT", "ERROR").contains(reason)) {
			throw DoraErrors.invalid("reason", "invalid");
		}
		CallRow call = repository.findCall(callId, sessionId).orElseThrow(() -> DoraErrors.notFound("call-not-found"));
		if ("FALLBACK".equals(reason) && !session.status().isOpen()) {
			throw DoraErrors.conflict("session-closed");
		}
		if ("OPEN".equals(call.status()) && calls.close(session, call, reason, false)) {
			switch (reason) {
				case "FALLBACK" -> {
					repository.setChannel(sessionId, "TURN_BASED");
					calls.event(session, "CHANNEL_FALLBACK", Map.of("from", "REALTIME", "to", "TURN_BASED"));
				}
				case "ERROR", "USER_LEFT" -> calls.event(session, "DISCONNECTED", Map.of("channel", "REALTIME"));
				default -> {
				}
			}
			if (session.status().isOpen()) {
				repository.touch(sessionId, clock.instant());
			}
		}
		SessionRow current = repository.findById(sessionId).orElseThrow(DoraErrors::sessionNotFound);
		Views.Session view = views.session(current, current.mode() == SessionMode.PRACTICE);
		return new Views.Hangup(view, SessionViews.nextPosition(current, repository.turns(sessionId)));
	}

	// ------------------------------------------------------------------------------------------ tiempo

	/** 012-49: en {@code endsAt + grace} el servidor cuelga y deja la sesión {@code COMPLETED} con {@code TIME_LIMIT}. */
	@Scheduled(fixedDelayString = "${talenti.ai.dora.realtime.sweep-interval:PT10S}",
			initialDelayString = "${talenti.ai.dora.realtime.sweep-interval:PT10S}")
	public void sweepTimeLimits() {
		for (SessionRow session : repository.realtimePastEnd(clock.instant().minus(properties.grace()))) {
			try {
				closeByTime(session);
			}
			catch (RuntimeException e) {
				log.warn("No se pudo cerrar por tiempo una sesión en tiempo real: {}", e.getClass().getName());
			}
		}
	}

	private boolean pastEnd(SessionRow session) {
		return session.endsAt() != null && clock.instant().isAfter(session.endsAt().plus(properties.grace()));
	}

	private void closeByTime(SessionRow session) {
		if (!session.status().isOpen()) {
			return;
		}
		calls.event(session, "TIME_LIMIT", Map.of("channel", "REALTIME"));
		lifecycle.close(session, SessionStatus.COMPLETED, "TIME_LIMIT");
	}

	// ------------------------------------------------------------------------------------------ latencia

	/** 012-55: p50 y p95 que reporta el navegador en el canal en tiempo real. Solo números. */
	public Views.Latency latency(int last) {
		List<Integer> values = repository.recentRealtimeLatencyMs(last);
		return new Views.Latency(com.talenti.practice.domain.Percentiles.of(values, 50),
				com.talenti.practice.domain.Percentiles.of(values, 95), values.size());
	}

	// ------------------------------------------------------------------------------------------ piezas

	private SessionRow owned(UUID userId, UUID sessionId) {
		return repository.findOwned(sessionId, userId).orElseThrow(DoraErrors::sessionNotFound);
	}

	private String firstName(SessionRow session) {
		return live.firstName(session.id(), () -> names.firstName(session.userId()).orElse(null));
	}

	private static Instant instant(String raw) {
		String value = raw.strip();
		try {
			if (value.matches("\\d{1,15}")) {
				return Instant.ofEpochMilli(Long.parseLong(value));
			}
			return Instant.parse(value);
		}
		catch (DateTimeParseException | NumberFormatException malformed) {
			return null;
		}
	}

	private static <E extends Enum<E>> E enumValue(Class<E> type, String raw) {
		if (raw == null) {
			return null;
		}
		try {
			return Enum.valueOf(type, raw.strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException unknown) {
			return null;
		}
	}

	/** Solo para las pruebas: vuelve a contar los límites por minuto. */
	public void resetLimits() {
		connects.reset();
		eventPosts.reset();
	}

}
