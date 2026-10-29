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
import com.talenti.exam.config.ProctoringProperties;
import com.talenti.exam.snapshot.ExamSnapshotService;
import com.talenti.profile.ProfileWatermarkName;
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
	private final ProctoringProperties proctoring;
	private final ExamSnapshotService snapshots;
	private final ProfileWatermarkName names;
	private final tools.jackson.databind.json.JsonMapper json;
	private final Clock clock;
	private final TransactionTemplate tx;
	private final SecureRandom random=new SecureRandom();
	private final Set<UUID> running=ConcurrentHashMap.newKeySet();
	public ExamAttemptService(ExamStore store,ApplicationStages stages,ExamAuthoringService authoring,ExamGradingService grading,
			CodeExecutionPort execution,ExamProperties properties,ExamNotice notice,ProctoringProperties proctoring,
			ExamSnapshotService snapshots,ProfileWatermarkName names,tools.jackson.databind.json.JsonMapper json,Clock clock,
			PlatformTransactionManager manager) {
		this.store=store; this.stages=stages; this.authoring=authoring; this.grading=grading; this.execution=execution;
		this.properties=properties; this.notice=notice; this.proctoring=proctoring; this.snapshots=snapshots; this.names=names;
		this.json=json; this.clock=clock; this.tx=new TransactionTemplate(manager);
	}
	/** Cuerpo de {@code start} (014-38). La comprobación es del navegador: el servidor no ve la cámara. */
	public record StartCommand(Boolean cameraReady,Boolean fullscreen,String detection,Map<String,Object> calibration) { }
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
		if(!notice.version().equals(version)) throw ExamErrors.conflict("notice-version-outdated");
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
	/** 014-37: un intento sin empezar que aceptó un aviso anterior acepta el vigente antes de {@code start}. */
	public ExamViews.Attempt acceptNotice(UUID user,UUID id,boolean accepted,String version) {
		return owned(user,id,true,a->{
			if(!accepted) throw ExamErrors.invalid("noticeAccepted","exam-notice-required");
			if(a.status()!=AttemptStatus.NOT_STARTED) throw ExamErrors.conflict("exam-already-started");
			if(!notice.version().equals(version)) throw ExamErrors.conflict("notice-version-outdated");
			if(!version.equals(a.noticeVersion())) {
				Instant now=clock.instant(); store.acceptNotice(id,version,now);
				store.event(id,"NOTICE_ACCEPTED",null,null,now,Map.of("version",version));
			}
			return view(store.findAttempt(id).orElseThrow());
		});
	}
	public ExamViews.Attempt start(UUID user,UUID id,StartCommand command) {
		return owned(user,id,true,a->{
			if(a.status()!=AttemptStatus.NOT_STARTED) throw ExamErrors.conflict("exam-already-started");
			if(!notice.version().equals(a.noticeVersion())) throw ExamErrors.conflict("notice-version-outdated");
			Instant now=clock.instant(), deadline=now.plus(properties.timeLimit());
			if(!supervised(a)) {
				store.start(id,now,deadline);
				store.event(id,"STARTED",null,null,now,Map.of());
				return view(store.findAttempt(id).orElseThrow());
			}
			if(command==null||!Boolean.TRUE.equals(command.cameraReady())||!Boolean.TRUE.equals(command.fullscreen()))
				throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"proctoring-required");
			ProctoringRules.Detection detection;
			try { detection=ProctoringRules.Detection.valueOf(Objects.requireNonNull(command.detection())); }
			catch(RuntimeException e) { throw ExamErrors.invalid("detection","invalid-detection"); }
			ProctoringRules.Calibration calibration=null;
			if(command.calibration()!=null) {
				if(!Set.of("baseYawDeg","basePitchDeg").containsAll(command.calibration().keySet())) throw ExamErrors.invalid("calibration","invalid-calibration");
				try { calibration=ProctoringRules.calibration(command.calibration().get("baseYawDeg"),command.calibration().get("basePitchDeg"),proctoring.thresholds().maxBaseDeg()); }
				catch(ProctoringRules.InvalidData e) { throw ExamErrors.invalid(e.field(),"invalid-calibration"); }
			}
			if(detection==ProctoringRules.Detection.AVAILABLE&&calibration==null) throw ExamErrors.invalid("calibration","required");
			store.start(id,now,deadline,proctoring.thresholdsVersion(),deliveredConfig(),detection.name(),calibration);
			store.event(id,"STARTED",null,null,now,Map.of());
			if(detection==ProctoringRules.Detection.UNAVAILABLE) store.event(id,"DETECTION_UNAVAILABLE",null,null,now,Map.of("source","start"));
			return view(store.findAttempt(id).orElseThrow());
		});
	}
	/** El intento se rinde con cámara si el aviso aceptado la informa y la supervisión está encendida. */
	private boolean supervised(AttemptRow a) { return proctoring.enabled()&&ProctoringRules.noticeIncludesCamera(a.noticeVersion()); }
	/** Lo que se entrega al navegador y se guarda en el intento al empezar (principio 11). */
	private Map<String,Object> deliveredConfig() {
		var m=new LinkedHashMap<String,Object>();
		m.put("snapshotInterval",proctoring.snapshotInterval().toSeconds()); m.put("snapshotMaxBytes",proctoring.snapshotMaxBytes().toBytes());
		m.put("snapshotMaxSide",proctoring.snapshotMaxSide()); m.put("maxSnapshots",proctoring.maxSnapshotsPerAttempt());
		m.put("thresholds",proctoring.clientThresholds());
		return m;
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
		return event(user,id,kind,clientEvent,clientAt,Map.of());
	}
	/** 014-14, 014-15, 014-51 y 014-52. Solo SCREEN_LEFT aplica la regla; los demás tipos solo se guardan. */
	public ExamViews.EventReply event(UUID user,UUID id,AttemptRules.EventKind kind,UUID clientEvent,Instant clientAt,Map<String,?> rawData) {
		if(kind==null||clientEvent==null||clientAt==null) throw ExamErrors.invalid("event","required");
		Map<String,Object> data;
		try { data=ProctoringRules.eventData(kind,rawData); }
		catch(ProctoringRules.InvalidData e) { throw ExamErrors.invalid(e.field().equals("data")?"data":"data."+e.field(),"invalid-event-data"); }
		var result=owned(user,id,true,a->{
			var duplicate=store.events(id).stream().filter(e->clientEvent.equals(e.clientEventId())).findFirst();
			if(duplicate.isPresent()) {
				if(!duplicate.get().kind().equals(kind.name())) throw ExamErrors.conflict("exam-event-conflict");
				return reply(a.status().closed()?AttemptRules.ScreenRule.CLOSED:kind==AttemptRules.EventKind.SCREEN_LEFT?AttemptRules.ScreenRule.WARNING:AttemptRules.ScreenRule.NONE,a.status());
			}
			requireRunning(a);
			Instant now=clock.instant();
			// 014-51: tope por minuto y por intento. SCREEN_LEFT nunca se rechaza: aplica la regla y ocurre dos veces como máximo.
			if(!ProctoringRules.changesAttempt(kind)&&store.clientEventsSince(id,now.minusSeconds(60))>=proctoring.eventsPerMinute())
				throw new TooManyRequestsException(60);
			var rule=AttemptRules.screenRule(kind,a.screenLeftCount());
			store.event(id,kind.name(),clientEvent,clientAt,now,data);
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
		Instant now=clock.instant();
		store.close(attempt.id(),status,reason,now);
		// 014-56: el plazo de las fotos corre desde el mismo instante del fin del intento.
		if(status!=AttemptStatus.CLOSED_WITHDRAWN) snapshots.scheduleFromEnd(attempt.id(),now);
		if(status==AttemptStatus.SUBMITTED||status==AttemptStatus.TIMED_OUT) store.event(attempt.id(),status.name(),null,null,now,Map.of());
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
			return new ExamViews.Question(q.id(),q.type().name(),q.prompt(),q.source().name(),q.type()==ExamQuestion.Type.CHOICE?item.options():List.of(),q.type()==ExamQuestion.Type.OPEN?q.rubric():List.of(),
					!code||q.language()==null?null:q.language().name(),!code||q.kind()==null?null:q.kind().name(),code?q.initialCode():null,
					!code?List.of():q.cases().stream().filter(ExamQuestion.Case::visible).map(c->new ExamViews.VisibleCase(c.id(),c.input(),c.expectedOutput(),c.setupSql(),c.tables(),c.expectedTable())).toList());
		}).toList();
		long seconds=a.status()==AttemptStatus.IN_PROGRESS?Math.max(0,Duration.between(clock.instant(),a.deadlineAt()).toSeconds()):0;
		return new ExamViews.Attempt(a.id(),a.applicationId(),a.status().name(),a.gradingStatus(),a.noticeVersion(),notice.version(),notice.text(),
				a.startedAt(),a.deadlineAt(),seconds,questions,
				store.answers(a.id()).stream().map(r->new ExamViews.Answer(r.questionId(),r.choiceId(),r.text(),r.code(),r.revision(),r.runCount(),r.updatedAt())).toList(),
				store.events(a.id()).stream().map(e->new ExamViews.Event(e.kind(),e.clientEventId(),e.clientOccurredAt(),e.occurredAt())).toList(),
				proctoringView(a),new ExamViews.Watermark(names.watermarkName(a.userId()).orElse(null),watermarkCode(a.applicationId())));
	}
	/** Duda E4: "T-" y los 6 primeros caracteres del id de la postulación, en mayúsculas. */
	static String watermarkCode(UUID application) { return "T-"+application.toString().substring(0,6).toUpperCase(Locale.ROOT); }
	@SuppressWarnings("unchecked")
	private ExamViews.Proctoring proctoringView(AttemptRow a) {
		boolean outdated=a.status()==AttemptStatus.NOT_STARTED&&!notice.version().equals(a.noticeVersion());
		if(a.proctoringVersion()!=null) {
			Map<String,Object> config=json.readValue(a.proctoringConfig(),Map.class);
			Map<String,Object> calibration=a.calibration()==null?null:json.readValue(a.calibration(),Map.class);
			return new ExamViews.Proctoring(true,false,a.proctoringVersion(),a.detectionStatus(),calibration,
					((Number)config.get("snapshotInterval")).longValue(),((Number)config.get("snapshotMaxBytes")).longValue(),
					((Number)config.get("snapshotMaxSide")).intValue(),((Number)config.get("maxSnapshots")).intValue(),
					(Map<String,Object>)config.get("thresholds"));
		}
		boolean upcoming=a.status()==AttemptStatus.NOT_STARTED&&proctoring.enabled()&&ProctoringRules.noticeIncludesCamera(notice.version());
		if(!upcoming) return new ExamViews.Proctoring(false,outdated,null,null,null,0,0,0,0,null);
		return new ExamViews.Proctoring(true,outdated,proctoring.thresholdsVersion(),null,null,proctoring.snapshotInterval().toSeconds(),
				proctoring.snapshotMaxBytes().toBytes(),proctoring.snapshotMaxSide(),proctoring.maxSnapshotsPerAttempt(),proctoring.clientThresholds());
	}
}
