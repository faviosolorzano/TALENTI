package com.talenti.practice.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.talenti.practice.config.DoraProperties;
import com.talenti.practice.domain.QuestionPlanner;
import com.talenti.practice.port.LlmPort;
import com.talenti.shared.ai.AiFunction;

/**
 * Planifica las preguntas de una sesión (012-02 a 012-04): UNA llamada a la IA con {@code question-timeout}; lo que
 * devuelve lo valida {@link QuestionPlanner}. Si la IA no responde a tiempo, falla o el gasto está agotado, todo sale
 * del banco base. Nunca falla por esto.
 */
@Component
public class SessionPlanning {

	private final LlmPort llm;

	private final QuestionPlanner planner;

	private final UsageMeter usage;

	private final ExecutorService executor;

	private final DoraProperties properties;

	SessionPlanning(LlmPort llm, QuestionPlanner planner, UsageMeter usage,
			@Qualifier("doraExecutor") ExecutorService executor, DoraProperties properties) {
		this.llm = llm;
		this.planner = planner;
		this.usage = usage;
		this.executor = executor;
		this.properties = properties;
	}

	public QuestionPlanner.Plan plan(UUID userId, LlmPort.Purpose purpose, List<QuestionPlanner.BasisRef> basis,
			int count) {
		if (usage.spendReached()) {
			return planner.plan(basis, count, null);
		}
		List<LlmPort.BasisItem> items = basis.stream().map(ref -> new LlmPort.BasisItem(ref.ref(), ref.label())).toList();
		int inputChars = items.stream().mapToInt(item -> item.ref().length() + item.label().length()).sum();
		Future<List<LlmPort.PlannedQuestion>> call = executor
				.submit(() -> llm.planQuestions(new LlmPort.PlanRequest(purpose, items, count)));
		List<QuestionPlanner.Proposal> proposals = null;
		try {
			List<LlmPort.PlannedQuestion> planned = call.get(properties.questionTimeout().toMillis(),
					TimeUnit.MILLISECONDS);
			proposals = new ArrayList<>();
			int outputChars = 0;
			for (LlmPort.PlannedQuestion question : planned == null ? List.<LlmPort.PlannedQuestion>of() : planned) {
				if (question != null) {
					proposals.add(new QuestionPlanner.Proposal(question.text(), question.basisRef()));
					outputChars += question.text() == null ? 0 : question.text().length();
				}
			}
			usage.llm(userId, AiFunction.DORA_DIALOG, llm.model(), inputChars, outputChars, true);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			call.cancel(true);
		}
		catch (ExecutionException | TimeoutException | RuntimeException e) {
			call.cancel(true);
			usage.llm(userId, AiFunction.DORA_DIALOG, llm.model(), inputChars, 0, false);
		}
		return planner.plan(basis, count, proposals);
	}

}
