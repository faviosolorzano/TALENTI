package com.talenti.practice.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.talenti.practice.config.DoraRealtimeProperties;
import com.talenti.practice.domain.AnswerAssembler;
import com.talenti.practice.domain.CallBudget;
import com.talenti.practice.domain.InputMode;
import com.talenti.practice.domain.RealtimePhase;
import com.talenti.practice.domain.SessionMode;
import com.talenti.practice.domain.StageMachine;
import com.talenti.practice.persistence.PracticeRepository;
import com.talenti.practice.persistence.PracticeRepository.CallRow;
import com.talenti.practice.persistence.PracticeRepository.QuestionRow;
import com.talenti.practice.persistence.PracticeRepository.SessionRow;
import com.talenti.practice.persistence.PracticeRepository.TurnRow;
import com.talenti.practice.persistence.PracticeRepository.UtteranceRow;
import com.talenti.practice.port.RealtimeCallPort;

import tools.jackson.databind.json.JsonMapper;

/**
 * Lo que queda de una conversación en tiempo real (spec 012, enmienda 2): arma una respuesta por pregunta con lo que
 * dijo la persona (012-46), cierra la llamada con los segundos medidos por el RELOJ DEL SERVIDOR y su consumo
 * {@code DORA_REALTIME} (012-50), y cuelga en el proveedor sin bloquear (012-51). Lo usa {@link SessionLifecycle} en
 * todo cierre de sesión (terminar, tiempo, inactividad, retiro, reinicio) para que ninguno lo olvide.
 *
 * <p>Nunca registra transcripciones, instrucciones, SDP ni el nombre (012-57): solo ids, conteos y tipos de error.
 */
@Component
public class RealtimeCalls {

	private static final Logger log = LoggerFactory.getLogger(RealtimeCalls.class);

	/** Estado de la conversación según lo guardado: etapa vigente, última pregunta y repreguntas por pregunta. */
	record Conversation(StageMachine.Stage current, int lastQuestion, Map<Integer, Integer> followUps,
			List<UtteranceRow> stages, List<UtteranceRow> rows) {

		Integer currentQuestion() {
			return current != null && current.isQuestion() ? current.position() : null;
		}

	}

	private final PracticeRepository repository;

	private final RealtimeCallPort port;

	private final FeedbackService feedback;

	private final UsageMeter usage;

	private final DoraRealtimeProperties properties;

	private final ExecutorService executor;

	private final TransactionTemplate transactions;

	private final JsonMapper mapper;

	private final Clock clock;

	RealtimeCalls(PracticeRepository repository, RealtimeCallPort port, FeedbackService feedback, UsageMeter usage,
			DoraRealtimeProperties properties, @Qualifier("doraExecutor") ExecutorService executor,
			TransactionTemplate transactions, JsonMapper mapper, Clock clock) {
		this.repository = repository;
		this.port = port;
		this.feedback = feedback;
		this.usage = usage;
		this.properties = properties;
		this.executor = executor;
		this.transactions = transactions;
		this.mapper = mapper;
		this.clock = clock;
	}

	// ------------------------------------------------------------------------------------------ estado

	static Conversation conversation(List<UtteranceRow> rows) {
		StageMachine.Stage current = null;
		int lastQuestion = 0;
		Map<Integer, Integer> followUps = new HashMap<>();
		List<UtteranceRow> stages = new ArrayList<>();
		for (UtteranceRow row : rows) {
			if (!"STAGE".equals(row.kind())) {
				continue;
			}
			stages.add(row);
			RealtimePhase phase = RealtimePhase.valueOf(row.phase());
			current = new StageMachine.Stage(phase, row.questionPosition());
			if (phase == RealtimePhase.QUESTION && row.questionPosition() != null) {
				lastQuestion = Math.max(lastQuestion, row.questionPosition());
			}
			if (phase == RealtimePhase.FOLLOW_UP && row.questionPosition() != null) {
				followUps.merge(row.questionPosition(), 1, Integer::sum);
			}
		}
		return new Conversation(current, lastQuestion, followUps, stages, rows);
	}

	// ------------------------------------------------------------------------------------------ armar respuestas

	/** El id de la respuesta armada de la pregunta {@code position}: el mismo siempre (rearmar no duplica). */
	static UUID assembledTurnId(UUID sessionId, int position) {
		return UUID.nameUUIDFromBytes(("realtime-answer:" + sessionId + ":" + position).getBytes(StandardCharsets.UTF_8));
	}

	/** Un turno armado o rearmado: hay que (re)calcular su retroalimentación. */
	record Assembled(UUID turnId, int position, boolean created, InputMode mode) {
	}

	/**
	 * Arma o rearma la respuesta de cada {@code positions} con lo que la persona dijo en {@code QUESTION(p)} y
	 * {@code FOLLOW_UP(p)} (012-46). Si la pregunta ya tiene una respuesta POR TURNOS, se respeta (no se mezclan). Si la
	 * persona corrigió la respuesta, no se rearma. Debe correr dentro de una transacción con la sesión bloqueada.
	 */
	List<Assembled> assemble(SessionRow session, Collection<Integer> positions, List<UtteranceRow> rows) {
		List<Assembled> result = new ArrayList<>();
		if (positions.isEmpty()) {
			return result;
		}
		List<QuestionRow> questions = repository.questions(session.id());
		List<TurnRow> turns = repository.turns(session.id());
		Instant now = clock.instant();
		for (int position : new TreeSet<>(positions)) {
			Optional<QuestionRow> question = questions.stream().filter(q -> q.position() == position).findFirst();
			if (question.isEmpty()) {
				continue;
			}
			List<AnswerAssembler.Piece> pieces = new ArrayList<>();
			rows.stream()
					.filter(r -> "UTTERANCE".equals(r.kind()) && "PERSON".equals(r.speaker())
							&& Integer.valueOf(position).equals(r.questionPosition())
							&& RealtimePhase.valueOf(r.phase()).isQuestion())
					.sorted(java.util.Comparator.comparing(UtteranceRow::occurredAt).thenComparingLong(UtteranceRow::seq))
					.forEach(r -> pieces.add(new AnswerAssembler.Piece(r.text(), "TEXT".equals(r.inputMode()))));
			AnswerAssembler.Answer answer = AnswerAssembler.assemble(pieces, properties.maxAnswer());
			if (answer == null) {
				continue; // sin respuesta: no se crea turno (principio 4)
			}
			InputMode mode = answer.typed() ? InputMode.TEXT : InputMode.VOICE;
			UUID id = assembledTurnId(session.id(), position);
			List<TurnRow> existing = turns.stream().filter(t -> t.position() == position).toList();
			if (existing.isEmpty()) {
				try {
					repository.insertAssembledTurn(id, question.get().id(), id, mode, answer.text(), now);
					result.add(new Assembled(id, position, true, mode));
				}
				catch (DuplicateKeyException race) {
					// Otro cierre la armó a la vez: queda la suya.
				}
			}
			else if (existing.size() == 1 && existing.get(0).id().equals(id)) {
				if (repository.updateAssembledTurn(id, mode, answer.text())) {
					result.add(new Assembled(id, position, false, mode));
				}
			}
		}
		return result;
	}

	/** Tras confirmar la transacción: retroalimentación (práctica) y hecho {@code ANSWERED} (entrevista). */
	void afterAssembled(SessionRow session, List<Assembled> assembled) {
		for (Assembled turn : assembled) {
			try {
				if (session.mode() == SessionMode.PRACTICE) {
					feedback.schedule(session.id(), turn.turnId(), session.userId(), SessionMode.PRACTICE);
				}
				else if (turn.created()) {
					event(session, "ANSWERED", Map.of("position", turn.position(), "inputMode", turn.mode().name(),
							"attempt", 1, "channel", "REALTIME"));
				}
			}
			catch (RuntimeException e) {
				log.warn("No se pudo anotar lo posterior a una respuesta en tiempo real: {}", e.getClass().getName());
			}
		}
	}

	/**
	 * Antes de cerrar la sesión: arma lo que falte (la pregunta en curso incluida, 012-46). En práctica dispara la
	 * retroalimentación; en entrevista la evaluación la lanza el cierre {@code COMPLETED} (012-25).
	 */
	public void beforeClose(SessionRow session) {
		try {
			if (!repository.hasRealtimeCalls(session.id())) {
				return;
			}
			List<Assembled> assembled = transactions.execute(tx -> {
				repository.lockSession(session.id());
				List<UtteranceRow> rows = repository.utterances(session.id());
				TreeSet<Integer> positions = new TreeSet<>();
				for (UtteranceRow row : rows) {
					if ("UTTERANCE".equals(row.kind()) && "PERSON".equals(row.speaker())
							&& row.questionPosition() != null) {
						positions.add(row.questionPosition());
					}
				}
				return assemble(session, positions, rows);
			});
			afterAssembled(session, assembled == null ? List.of() : assembled);
		}
		catch (RuntimeException e) {
			log.warn("No se pudieron armar las respuestas en tiempo real al cerrar: {}", e.getClass().getName());
		}
	}

	// ------------------------------------------------------------------------------------------ cerrar llamadas

	/**
	 * Cierra la llamada abierta de la sesión, si hay: segundos por el reloj del servidor (acotados al fin + gracia), una
	 * fila {@code DORA_REALTIME} con su costo estimado y {@code hangup} en el proveedor sin bloquear.
	 *
	 * @param untilLastActivity tras un reinicio, el tiempo cuenta hasta la última actividad (012-50)
	 * @return la llamada cerrada, si había una abierta
	 */
	public Optional<CallRow> closeOpenCall(SessionRow session, String reason, boolean untilLastActivity) {
		Optional<CallRow> open = repository.openCall(session.id());
		open.ifPresent(call -> close(session, call, reason, untilLastActivity));
		return open;
	}

	/** Cierra una llamada concreta (si sigue abierta). Falso si ya estaba cerrada. */
	public boolean close(SessionRow session, CallRow call, String reason, boolean untilLastActivity) {
		Instant end = clock.instant();
		if (untilLastActivity && session.lastActivityAt() != null) {
			end = session.lastActivityAt();
		}
		if (session.endsAt() != null) {
			Instant cap = session.endsAt().plus(properties.grace());
			if (end.isAfter(cap)) {
				end = cap;
			}
		}
		if (end.isBefore(call.startedAt())) {
			end = call.startedAt();
		}
		long millis = Duration.between(call.startedAt(), end).toMillis();
		int seconds = (int) Math.min(Integer.MAX_VALUE, (millis + 999) / 1000);
		if (!repository.closeCall(call.id(), reason, end, seconds)) {
			return false;
		}
		RealtimeCallPort.Info info = port.info();
		usage.realtime(call.id(), session.userId(), info.adapter(), info.model(), seconds,
				CallBudget.costMicros(seconds, properties.pricePerMinute()));
		hangupQuietly(call.providerCallId());
		return true;
	}

	/** Cuelga en el proveedor en otro hilo: si falla, la llamada igual queda cerrada aquí (012-49, 012-51). */
	void hangupQuietly(String providerCallId) {
		if (providerCallId == null) {
			return;
		}
		executor.execute(() -> {
			try {
				port.hangup(providerCallId);
			}
			catch (RuntimeException e) {
				log.warn("No se pudo colgar una llamada en tiempo real en el proveedor: {}", e.getClass().getName());
			}
		});
	}

	/** Un hecho de la entrevista (solo inserciones, sin contenido). En la práctica no se anota nada. */
	void event(SessionRow session, String kind, Map<String, Object> data) {
		if (session.mode() == SessionMode.INTERVIEW) {
			repository.addEvent(session.id(), kind, mapper.writeValueAsString(data), clock.instant());
		}
	}

}
