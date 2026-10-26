package com.talenti.exam.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.talenti.exam.*;
import com.talenti.exam.config.ExamProperties;
import com.talenti.exam.domain.*;
import com.talenti.exam.persistence.ExamStore;
import com.talenti.exam.persistence.ExamStore.*;
import com.talenti.exam.port.*;
import com.talenti.shared.ai.AiFunction;

@Service
public class ExamGradingService implements ExamResults {
	private final ExamStore store;
	private final OpenAnswerGraderPort grader;
	private final CodeExecutionPort execution;
	private final ExamProperties properties;
	private final ExamUsage usage;
	private final ExecutorService executor;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final ExamScoring scoring;
	private final Set<UUID> queued=ConcurrentHashMap.newKeySet();
	public ExamGradingService(ExamStore store,@Qualifier("examOpenGrader") OpenAnswerGraderPort grader,CodeExecutionPort execution,ExamProperties properties,
			ExamUsage usage,@Qualifier("examExecutor") ExecutorService executor,PlatformTransactionManager manager,Clock clock) {
		this.store=store; this.grader=grader; this.execution=execution; this.properties=properties; this.usage=usage;
		this.executor=executor; this.tx=new TransactionTemplate(manager); this.clock=clock; this.scoring=new ExamScoring(properties.scoring().rules());
	}
	public void schedule(UUID id) { if(queued.add(id)) executor.execute(()->{try { grade(id); } finally { queued.remove(id); }}); }
	@EventListener(ApplicationReadyEvent.class)
	public void resume() { retryPending(); }
	@Scheduled(fixedDelayString="${talenti.exam.retry-interval:PT1M}",initialDelayString="${talenti.exam.retry-interval:PT1M}")
	public void retryPending() { store.needsGrading(clock.instant()).forEach(a->schedule(a.id())); }
	private void grade(UUID id) {
		var attempt=store.findAttempt(id).orElse(null);
		if(attempt==null||!attempt.status().closed()||attempt.status()==AttemptStatus.CLOSED_WITHDRAWN) return;
		Map<UUID,ExamQuestion> questions=new HashMap<>(); store.questions(attempt.versionId()).forEach(q->questions.put(q.id(),q));
		for(var answer:store.answers(id)) {
			if(!store.claimGrade(answer.id(),clock.instant(),clock.instant().plus(Duration.ofMinutes(5)))) continue;
			var q=questions.get(answer.questionId());
			String status="DONE"; BigDecimal points=null; Object detail=Map.of(); java.time.Instant retry=null;
			try {
				boolean empty=answer.choiceId()==null&&(answer.text()==null||answer.text().isBlank())&&(answer.code()==null||answer.code().isBlank());
				if(empty) { status="UNANSWERED"; points=BigDecimal.ZERO.setScale(2); detail=Map.of("reason","UNANSWERED"); }
				else if(q.type()==ExamQuestion.Type.CHOICE) { points=ExamScoring.choice(q,answer.choiceId()); detail=Map.of("rule","CHOICE_ID","scoringVersion",ExamScoring.VERSION); }
				else if(q.type()==ExamQuestion.Type.CODE) {
					var results=execution.run(q.language(),answer.code(),q.cases(),properties.runner().limits());
					var grade=scoring.code(q,answer.code(),results); points=grade.points(); detail=grade;
				}
				else {
					if(!usage.available()) throw new IllegalStateException("exam-budget-exhausted");
					var request=new OpenAnswerGraderPort.Request(q.prompt(),q.rubric(),q.keyPoints(),Objects.toString(answer.text(),""));
					Future<List<OpenAnswerGraderPort.Proposed>> pending=executor.submit(()->usage.call(attempt.userId(),AiFunction.EXAM_GRADING,
							grader.model(),request,()->grader.grade(request)));
					List<OpenAnswerGraderPort.Proposed> proposed;
					try { proposed=pending.get(properties.gradingTimeout().toMillis(),TimeUnit.MILLISECONDS); }
					finally { if(!pending.isDone()) pending.cancel(true); }
					var grade=OpenAnswerScoring.validate(q,Objects.toString(answer.text(),""),proposed,properties.weights().rubric());
					if(!grade.complete()) throw new IllegalStateException("exam-grade-invalid");
					points=grade.points(); detail=Map.of("evaluation",grade,"model",grader.model(),"promptVersion",grader.promptVersion());
				}
			}
			catch(InterruptedException e) { Thread.currentThread().interrupt(); return; }
			catch(ExecutionException|TimeoutException|RuntimeException e) {
				points=null;
				Duration window=q.type()==ExamQuestion.Type.CODE?properties.runnerRetryWindow():properties.gradingRetryWindow();
				status=clock.instant().isBefore(attempt.endedAt().plus(window))?"PENDING":"UNAVAILABLE";
				detail=Map.of("reason",status.equals("PENDING")?"PLATFORM_RETRY":"HUMAN_REVIEW_REQUIRED","generatedBy",q.type()==ExamQuestion.Type.OPEN?"AI":"CODE");
				if(status.equals("PENDING")) retry=clock.instant().plusSeconds(Math.min(3600,30L<<Math.min(7,answer.gradingAttempts())));
			}
			final String savedStatus=status; final BigDecimal savedPoints=points; final Object savedDetail=detail; final var savedRetry=retry;
			tx.executeWithoutResult(transaction->{
				var current=store.lockAttempt(id).orElse(null);
				if(current==null||current.status()==AttemptStatus.CLOSED_WITHDRAWN) return;
				store.grade(answer.id(),savedStatus,savedPoints,savedDetail,savedRetry);
				recalculate(current);
			});
		}
	}
	private void recalculate(AttemptRow attempt) {
		List<AnswerRow> answers=store.answers(attempt.id());
		BigDecimal total=answers.stream().map(AnswerRow::points).filter(Objects::nonNull).reduce(BigDecimal.ZERO,BigDecimal::add).min(BigDecimal.valueOf(100)).setScale(2,java.math.RoundingMode.HALF_UP);
		boolean pending=answers.stream().anyMatch(a->a.gradeStatus().equals("PENDING")||a.gradeStatus().equals("UNAVAILABLE"));
		store.total(attempt.id(),pending?"PROVISIONAL":"DONE",total,ExamScoring.score20(total));
	}
	@Override public Optional<ExamResults.Result> forApplication(UUID applicationId) {
		return store.forApplication(applicationId).map(a->{
			Map<UUID,ExamQuestion> questions=new HashMap<>(); store.questions(a.versionId()).forEach(q->questions.put(q.id(),q));
			List<AnswerRow> answers=store.answers(a.id());
			BigDecimal pending=answers.stream().filter(r->r.points()==null).map(r->questions.get(r.questionId()).points()).reduce(BigDecimal.ZERO,BigDecimal::add);
			return new ExamResults.Result(a.id(),a.versionId(),a.status().name(),a.gradingStatus(),a.closedReason(),a.score100(),a.score20(),pending,a.scoringVersion(),
					answers.stream().map(r->{var q=questions.get(r.questionId()); return new ExamResults.Question(q.id(),q.type().name(),q.prompt(),r.choiceId(),r.text(),r.code(),r.gradeStatus(),r.points(),r.gradeDetail());}).toList(),
					store.events(a.id()).stream().map(e->new ExamResults.Event(e.kind(),e.occurredAt(),e.clientOccurredAt(),e.dataJson())).toList(),
					answers.stream().anyMatch(r->r.gradeStatus().equals("UNANSWERED")));
		});
	}
}
