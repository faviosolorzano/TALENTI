package com.talenti.practice.service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.talenti.practice.config.DoraProperties;
import com.talenti.practice.domain.Rubric;
import com.talenti.practice.domain.RubricValidator;
import com.talenti.practice.domain.SessionMode;
import com.talenti.practice.persistence.PracticeRepository;
import com.talenti.practice.persistence.PracticeRepository.QuestionRow;
import com.talenti.practice.persistence.PracticeRepository.TurnRow;
import com.talenti.practice.port.LlmPort;
import com.talenti.shared.ai.AiFunction;

/**
 * Retroalimentación por rúbrica (012-18 a 012-20) y evaluación de la entrevista (012-25), FUERA del camino de la voz:
 * cada respuesta se evalúa en un hilo aparte con {@code feedback-timeout}. La IA recibe SOLO la pregunta, la
 * transcripción final y la rúbrica: nunca el nombre ni otro dato de la persona (012-24). Lo que devuelve lo valida
 * {@link RubricValidator}. Si la IA cae, tarda o se agotó el gasto, queda {@code UNAVAILABLE} y se puede reintentar.
 */
@Service
public class FeedbackService {

	private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

	private final PracticeRepository repository;

	private final LlmPort llm;

	private final Rubric rubric;

	private final RubricValidator validator;

	private final SessionViews views;

	private final UsageMeter usage;

	private final ExecutorService executor;

	private final DoraProperties properties;

	private final Clock clock;

	FeedbackService(PracticeRepository repository, LlmPort llm, Rubric rubric, SessionViews views, UsageMeter usage,
			@Qualifier("doraExecutor") ExecutorService executor, DoraProperties properties, Clock clock) {
		this.repository = repository;
		this.llm = llm;
		this.rubric = rubric;
		this.validator = new RubricValidator(rubric);
		this.views = views;
		this.usage = usage;
		this.executor = executor;
		this.properties = properties;
		this.clock = clock;
	}

	/** Marca la respuesta como pendiente y la evalúa en segundo plano. */
	public void schedule(UUID sessionId, UUID turnId, UUID userId, SessionMode mode) {
		repository.saveFeedback(turnId, "PENDING", "[]", null, llm.model(), llm.promptVersion(), rubric.version(),
				clock.instant());
		executor.execute(() -> evaluate(sessionId, turnId, userId, mode));
	}

	/** Reintento (012-19): solo si estaba {@code UNAVAILABLE} (o nunca se pidió). */
	public boolean retry(UUID sessionId, UUID turnId, UUID userId, SessionMode mode) {
		if (!repository.markFeedbackPending(turnId, llm.promptVersion(), rubric.version(), clock.instant())) {
			return false;
		}
		executor.execute(() -> evaluate(sessionId, turnId, userId, mode));
		return true;
	}

	/** Evalúa ahora (en el hilo que llama). Nunca lanza. */
	public void evaluate(UUID sessionId, UUID turnId, UUID userId, SessionMode mode) {
		try {
			Optional<TurnRow> found = repository.findTurn(turnId, sessionId);
			if (found.isEmpty()) {
				return;
			}
			TurnRow turn = found.get();
			String question = repository.questions(sessionId).stream()
					.filter(q -> q.position() == turn.position()).map(QuestionRow::text).findFirst().orElse("");
			AiFunction function = mode == SessionMode.INTERVIEW ? AiFunction.INTERVIEW_EVALUATION
					: AiFunction.PRACTICE_FEEDBACK;
			RubricValidator.Result result = usage.spendReached() ? null : ask(question, turn, userId, function, mode);
			if (!stillCurrent(turn)) {
				return; // la persona corrigió la transcripción: la evaluación nueva ya va en camino
			}
			if (result == null || !result.usable()) {
				repository.saveFeedbackIfCurrent(turn, "UNAVAILABLE", "[]", null, llm.model(), llm.promptVersion(),
						rubric.version(), clock.instant());
			}
			else {
				repository.saveFeedbackIfCurrent(turn, "READY", views.json(result.criteria()), result.total(), llm.model(),
						llm.promptVersion(), rubric.version(), clock.instant());
			}
		}
		catch (RuntimeException e) {
			log.warn("No se pudo guardar la retroalimentación: {}", e.getClass().getName());
		}
	}

	private RubricValidator.Result ask(String question, TurnRow turn, UUID userId, AiFunction function,
			SessionMode mode) {
		List<LlmPort.CriterionSpec> specs = rubric.criteria().stream()
				.map(criterion -> new LlmPort.CriterionSpec(criterion.code(), criterion.description())).toList();
		LlmPort.Purpose purpose = mode == SessionMode.INTERVIEW ? LlmPort.Purpose.INTERVIEW : LlmPort.Purpose.PRACTICE;
		LlmPort.EvaluationRequest request = new LlmPort.EvaluationRequest(purpose, question, turn.transcriptFinal(), specs);
		int inputChars = question.length() + turn.transcriptFinal().length();
		Future<LlmPort.RawEvaluation> call = executor.submit(() -> llm.evaluate(request));
		try {
			LlmPort.RawEvaluation raw = call.get(properties.feedbackTimeout().toMillis(), TimeUnit.MILLISECONDS);
			List<RubricValidator.Proposed> proposed = raw.criteria().stream()
					.map(c -> new RubricValidator.Proposed(c.criterion(), c.level(), c.comment(), c.quotes())).toList();
			int outputChars = raw.criteria().stream()
					.mapToInt(c -> (c.comment() == null ? 0 : c.comment().length())
							+ (c.quotes() == null ? 0 : c.quotes().stream().mapToInt(q -> q == null ? 0 : q.length()).sum()))
					.sum();
			usage.llm(userId, function, llm.model(), inputChars, outputChars, true);
			return validator.validate(proposed, turn.transcriptFinal());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			call.cancel(true);
			return null;
		}
		catch (ExecutionException | TimeoutException | RuntimeException e) {
			call.cancel(true);
			usage.llm(userId, function, llm.model(), inputChars, 0, false);
			return null;
		}
	}

	private boolean stillCurrent(TurnRow evaluated) {
		return repository.findTurn(evaluated.id(), evaluated.sessionId())
				.map(now -> now.transcriptFinal().equals(evaluated.transcriptFinal())).orElse(false);
	}

}
