package com.talenti.exam.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.*;
import org.springframework.transaction.support.TransactionTemplate;
import com.talenti.application.ApplicationStages;
import com.talenti.exam.config.ExamProperties;
import com.talenti.exam.domain.*;
import com.talenti.exam.persistence.ExamStore;
import com.talenti.exam.persistence.ExamStore.*;
import com.talenti.exam.port.CodeExecutionPort;
import com.talenti.shared.events.ApplicationWithdrawn;
import com.talenti.shared.web.*;

@Service
public class ExamAttemptService {
	private final ExamStore store;
	private final ApplicationStages stages;
	private final ExamAuthoringService authoring;
	private final ExamGradingService grading;
	private final CodeExecutionPort execution;
	private final ExamProperties properties;
	private final ExamNotice notice;
	private final Clock clock;
	private final TransactionTemplate tx;
	private final SecureRandom random=new SecureRandom();
	private final Set<UUID> running=ConcurrentHashMap.newKeySet();
	public ExamAttemptService(ExamStore store,ApplicationStages stages,ExamAuthoringService authoring,ExamGradingService grading,
			CodeExecutionPort execution,ExamProperties properties,ExamNotice notice,Clock clock,PlatformTransactionManager manager) {
		this.store=store; this.stages=stages; this.authoring=authoring; this.grading=grading; this.execution=execution;
		this.properties=properties; this.notice=notice; this.clock=clock; this.tx=new TransactionTemplate(manager);
	}
	public record Creation(ExamViews.Attempt attempt,ExamViews.Preparation preparing) { }
	public record AnswerCommand(String choiceId,String text,String code,Long revision) {
		@Override public String toString() { return "ExamAnswerCommand[...]"; }
	}
	private record Outcome<T>(T data,boolean closedNow,String error) { }
	private record Execution(AttemptRow attempt,ExamQuestion question,AnswerRow answer) { }

	public Creation create(UUID user,UUID application,boolean accepted,String version) {
		var owned=stages.findOwned(application,user).orElseThrow(ExamErrors::missing);
		if(store.forApplication(application,user).isPresent()) throw ExamErrors.conflict("exam-exists");
		if(owned.stage()!=ApplicationStages.Stage.SUBMITTED) throw ExamErrors.conflict("exam-not-allowed");
		if(!accepted) throw ExamErrors.invalid("noticeAccepted","exam-notice-required");
		if(!notice.version().equals(version)) throw ExamErrors.conflict("exam-notice-outdated");
		var set=authoring.ensure(owned.vacancyId());
		if(!set.status().equals("READY")) return new Creation(null,new ExamViews.Preparation("PREPARING",2));
		try {
			var result=tx.execute(status->{
				if(!stages.startExam(application,user)) throw ExamErrors.conflict("exam-not-allowed");
				var versions=store.versions(set.id());
				if(versions.size()!=3) throw ExamErrors.conflict("exam-set-busy");
				return store.insertAttempt(application,user,versions.get(random.nextInt(3)).id(),random.nextLong(),version,clock.instant());
			});
			return new Creation(view(result),null);
		}
		catch(DuplicateKeyException e) { throw ExamErrors.conflict("exam-exists"); }
	}
	public Optional<ExamViews.Attempt> forApplication(UUID user,UUID application) {
		stages.findOwned(application,user).orElseThrow(ExamErrors::missing);
		return store.forApplication(application,user).map(a->get(user,a.id()));
	}
	public ExamViews.Attempt get(UUID user,UUID id) { return owned(user,id,false,this::view); }
	public ExamViews.Attempt start(UUID user,UUID id) {
		return owned(user,id,true,a->{
			if(a.status()!=AttemptStatus.NOT_STARTED) throw ExamErrors.conflict("exam-already-started");
			Instant now=clock.instant(); store.start(id,now,now.plus(properties.timeLimit()));
			store.event(id,"STARTED",null,null,now,Map.of());
			return view(store.findAttempt(id).orElseThrow());
		});
	}
	public ExamViews.Save save(UUID user,UUID id,UUID questionId,AnswerCommand command) {
		return owned(user,id,true,a->{
			requireRunning(a); ExamQuestion q=question(a,questionId);
			if(command.revision()==null||command.revision()<0) throw ExamErrors.invalid("revision","required");
			int supplied=(command.choiceId()==null?0:1)+(command.text()==null?0:1)+(command.code()==null?0:1);
			if(supplied>1||command.choiceId()!=null&&q.type()!=ExamQuestion.Type.CHOICE
					||command.text()!=null&&q.type()!=ExamQuestion.Type.OPEN||command.code()!=null&&q.type()!=ExamQuestion.Type.CODE)
				throw ExamErrors.invalid("answer","invalid-answer-type");
			if(command.choiceId()!=null&&q.options().stream().noneMatch(o->o.id().equals(command.choiceId()))) throw ExamErrors.invalid("choiceId","unknown-option");
			if(command.text()!=null&&command.text().length()>4000) throw ExamErrors.invalid("text","max-length",4000);
			if(command.code()!=null&&command.code().getBytes(StandardCharsets.UTF_8).length>properties.runner().codeBytes())
				throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE,"exam-code-too-large");
			Instant now=clock.instant();
			if(!store.saveAnswer(id,questionId,command.revision(),command.choiceId(),command.text(),command.code(),now)) throw ExamErrors.conflict("stale-revision");
			return new ExamViews.Save(command.revision()+1,now);
		});
	}
	public ExamViews.Attempt submit(UUID user,UUID id) {
		var result=owned(user,id,true,a->{ requireRunning(a); closeLocked(a,AttemptStatus.SUBMITTED,null); return view(store.findAttempt(id).orElseThrow()); });
		grading.schedule(id); return result;
	}
	public ExamViews.EventReply event(UUID user,UUID id,AttemptRules.EventKind kind,UUID clientEvent,Instant clientAt) {
		if(kind==null||clientEvent==null||clientAt==null) throw ExamErrors.invalid("event","required");
		var result=owned(user,id,true,a->{
			var duplicate=store.events(id).stream().filter(e->clientEvent.equals(e.clientEventId())).findFirst();
			if(duplicate.isPresent()) {
				if(!duplicate.get().kind().equals(kind.name())) throw ExamErrors.conflict("exam-event-conflict");
				return reply(a.status().closed()?AttemptRules.ScreenRule.CLOSED:kind==AttemptRules.EventKind.SCREEN_LEFT?AttemptRules.ScreenRule.WARNING:AttemptRules.ScreenRule.NONE,a.status());
			}
			requireRunning(a);
			var rule=AttemptRules.screenRule(kind,a.screenLeftCount());
			Instant now=clock.instant();
			store.event(id,kind.name(),clientEvent,clientAt,now,Map.of());
			if(kind==AttemptRules.EventKind.SCREEN_LEFT) store.incrementScreenLeft(id);
			if(rule==AttemptRules.ScreenRule.WARNING) store.event(id,"WARNING_ISSUED",null,null,now,Map.of("screenLeftCount",1));
			if(rule==AttemptRules.ScreenRule.CLOSED) {
				closeLocked(a,AttemptStatus.CLOSED_BY_RULE,"SCREEN_LEFT_TWICE");
				store.event(id,"CLOSED_BY_RULE",null,null,now,Map.of("screenLeftCount",2));
			}
			return reply(rule,rule==AttemptRules.ScreenRule.CLOSED?AttemptStatus.CLOSED_BY_RULE:a.status());
		});
		if(result.rule().equals("CLOSED")) grading.schedule(id);
		return result;
	}
	private static ExamViews.EventReply reply(AttemptRules.ScreenRule rule,AttemptStatus status) {
		return new ExamViews.EventReply(rule.name(),rule==AttemptRules.ScreenRule.WARNING
				?"Saliste de la pantalla del examen. Si vuelves a salir, pierdes el examen.":null,status.name());
	}
	public ExamViews.Run run(UUID user,UUID id,UUID questionId) {
		if(!running.add(user)) throw ExamErrors.conflict("exam-run-in-progress");
		Execution job=null;
		try {
			job=owned(user,id,true,a->{
				requireRunning(a); var q=question(a,questionId);
				if(q.type()!=ExamQuestion.Type.CODE) throw ExamErrors.conflict("exam-not-code");
				var answer=store.answer(id,questionId).orElseThrow(ExamErrors::missing);
				if(answer.code()==null||answer.code().isBlank()) throw ExamErrors.invalid("code","required");
				if(!store.consumeRun(answer.id(),properties.maxRunsPerQuestion())) throw new TooManyRequestsException(60);
				return new Execution(a,q,answer);
			});
			var q=job.question(); var visible=q.cases().stream().filter(ExamQuestion.Case::visible).toList();
			var results=execution.run(q.language(),job.answer().code(),visible,properties.runner().limits());
			if(results.size()!=visible.size()) throw new CodeExecutionPort.Unavailable();
			List<ExamViews.CaseResult> view=new ArrayList<>();
			for(int i=0;i<results.size();i++) {
				var r=results.get(i); var c=visible.get(i);
				boolean passed=r.completed()&&(q.language()==ExamQuestion.Language.SQL?r.table()!=null&&c.expectedTable()!=null
						&&SqlSimilarity.compare(c.expectedTable(),r.table(),q.orderMatters(),properties.scoring().sqlColumnsWeight())>=1
						:ExamScoring.normalize(r.output()).equals(ExamScoring.normalize(c.expectedOutput())));
				var table=r.table()==null?null:new ExamQuestion.SqlTable(r.table().columns(),r.table().rows().stream().limit(50).toList(),r.table().types());
				view.add(new ExamViews.CaseResult(passed,r.output(),r.error()==CodeExecutionPort.Error.NONE?null:r.error().name(),r.ms(),table));
			}
			return new ExamViews.Run(List.copyOf(view));
		}
		catch(CodeExecutionPort.Unavailable e) { if(job!=null) store.refundRun(job.answer().id()); throw ExamErrors.unavailable("runner-busy"); }
		finally { running.remove(user); }
	}
	private <T> T owned(UUID user,UUID id,boolean write,Function<AttemptRow,T> action) {
		Outcome<T> result=tx.execute(status->{
			AttemptRow a=store.ownedAttempt(id,user,true).orElseThrow(ExamErrors::missing);
			boolean expired=AttemptRules.expired(a.status(),a.deadlineAt(),clock.instant());
			if(expired) { closeLocked(a,AttemptStatus.TIMED_OUT,"DEADLINE"); a=store.findAttempt(id).orElseThrow(); }
			if(expired&&write) return new Outcome<>(null,true,"exam-closed");
			return new Outcome<>(action.apply(a),expired,null);
		});
		if(result.closedNow()) grading.schedule(id);
		if(result.error()!=null) throw ExamErrors.conflict(result.error());
		return result.data();
	}
	private void closeLocked(AttemptRow attempt,AttemptStatus status,String reason) {
		if(status!=AttemptStatus.CLOSED_WITHDRAWN&&!stages.completeExam(attempt.applicationId(),attempt.userId())) throw ExamErrors.conflict("exam-application-state");
		store.close(attempt.id(),status,reason,clock.instant());
		if(status==AttemptStatus.SUBMITTED||status==AttemptStatus.TIMED_OUT) store.event(attempt.id(),status.name(),null,null,clock.instant(),Map.of());
	}
	private static void requireRunning(AttemptRow a) { if(a.status()!=AttemptStatus.IN_PROGRESS) throw ExamErrors.conflict("exam-closed"); }
	private ExamQuestion question(AttemptRow a,UUID id) { return store.questions(a.versionId()).stream().filter(q->q.id().equals(id)).findFirst().orElseThrow(ExamErrors::missing); }
	@Scheduled(fixedDelayString="${talenti.exam.sweep-interval:PT1M}",initialDelayString="${talenti.exam.sweep-interval:PT1M}")
	public void expire() { for(var a:store.expired(clock.instant())) owned(a.userId(),a.id(),false,current->null); }
	@TransactionalEventListener(phase=TransactionPhase.AFTER_COMMIT,fallbackExecution=true)
	@org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
	public void withdrawn(ApplicationWithdrawn event) {
		store.forApplication(event.applicationId()).ifPresent(a->tx.executeWithoutResult(status->{
			var current=store.lockAttempt(a.id()).orElse(null);
			if(current!=null&&!current.status().closed()) {
				store.close(current.id(),AttemptStatus.CLOSED_WITHDRAWN,"WITHDRAWN",clock.instant());
				store.event(current.id(),"WITHDRAWN",null,null,clock.instant(),Map.of());
			}
		}));
	}
	private ExamViews.Attempt view(AttemptRow a) {
		List<ExamViews.Question> questions=a.startedAt()==null?List.of():ExamShuffle.order(store.questions(a.versionId()),a.shuffleSeed()).stream().map(item->{
			var q=item.question(); boolean code=q.type()==ExamQuestion.Type.CODE;
			return new ExamViews.Question(q.id(),q.type().name(),q.prompt(),q.type()==ExamQuestion.Type.CHOICE?item.options():List.of(),q.type()==ExamQuestion.Type.OPEN?q.rubric():List.of(),
					!code||q.language()==null?null:q.language().name(),!code||q.kind()==null?null:q.kind().name(),code?q.initialCode():null,
					!code?List.of():q.cases().stream().filter(ExamQuestion.Case::visible).map(c->new ExamViews.VisibleCase(c.id(),c.input(),c.expectedOutput(),c.setupSql(),c.tables(),c.expectedTable())).toList());
		}).toList();
		long seconds=a.status()==AttemptStatus.IN_PROGRESS?Math.max(0,Duration.between(clock.instant(),a.deadlineAt()).toSeconds()):0;
		return new ExamViews.Attempt(a.id(),a.applicationId(),a.status().name(),a.gradingStatus(),a.noticeVersion(),notice.text(),a.startedAt(),a.deadlineAt(),seconds,questions,
				store.answers(a.id()).stream().map(r->new ExamViews.Answer(r.questionId(),r.choiceId(),r.text(),r.code(),r.revision(),r.runCount(),r.updatedAt())).toList(),
				store.events(a.id()).stream().map(e->new ExamViews.Event(e.kind(),e.clientEventId(),e.clientOccurredAt(),e.occurredAt())).toList());
	}
}
