package com.talenti.exam;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.talenti.exam.adapter.*;
import com.talenti.exam.domain.*;
import com.talenti.exam.persistence.ExamStore;
import com.talenti.exam.service.*;
import com.talenti.matching.MatchingIntegrationTest;
import com.talenti.support.ApiClient;
import com.talenti.identity.Role;
import tools.jackson.databind.JsonNode;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"talenti.demo-data.enabled=true","talenti.exam.runner.mode=simulated"})
class ExamFlowTests extends MatchingIntegrationTest {
	@Autowired ExamStore store;
	@Autowired ExamResults results;
	@Autowired ExamAuthoringService authoring;
	@Autowired ExamGradingService grading;
	@Autowired SimulatedOpenAnswerGrader grader;
	@Autowired SimulatedCodeExecution execution;
	@Autowired SimulatedExamAuthoring author;
	private record Fixture(RegisteredStudent student,UUID application,UUID attempt) { }
	@AfterEach void reset() { grader.reset();execution.reset();author.reset();clock.set(Instant.now()); }
	private UUID application(RegisteredStudent student,boolean supported) {
		UUID vacancy=UUID.randomUUID(),application=UUID.randomUUID();
		insertVacancyWithJava("Examen "+vacancy,OffsetDateTime.now(),"OPEN",vacancy,supported);
		jdbc.update("INSERT INTO application(id,user_id,vacancy_id,status,submitted_at) VALUES (?,?,?,'SUBMITTED',now())",application,student.id(),vacancy);
		return application;
	}
	private Fixture create() {
		RegisteredStudent student=registerStudent(); UUID application=application(student,true);
		long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
		while(true) {
			var response=student.api().post("/api/v1/applications/"+application+"/exam",Map.of("noticeAccepted",true,"noticeVersion","v1"));
			if(response.status()==201) return new Fixture(student,application,UUID.fromString(response.json().get("id").asString()));
			assertThat(response.status()).as(response.body()).isEqualTo(202);
			assertThat(response.json().get("status").asString()).isEqualTo("PREPARING");
			if(System.nanoTime()>deadline) throw new AssertionError("El conjunto no terminó de prepararse");
			try { Thread.sleep(30); } catch(InterruptedException e) { throw new IllegalStateException(e); }
		}
	}
	private static String route(Fixture f) { return "/api/v1/exam-attempts/"+f.attempt(); }
	private List<ExamQuestion> questions(Fixture f) { return store.questions(store.findAttempt(f.attempt()).orElseThrow().versionId()); }
	private void start(Fixture f) { assertThat(f.student().api().post(route(f)+"/start",null).status()).isEqualTo(200); }
	private static void await(BooleanSupplier check) {
		long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
		while(!check.getAsBoolean()) { if(System.nanoTime()>deadline) throw new AssertionError("No terminó la operación asíncrona"); try {Thread.sleep(25);}catch(InterruptedException e){throw new IllegalStateException(e);} }
	}
	private void done(Fixture f) { await(()->results.forApplication(f.application()).orElseThrow().gradingStatus().equals("DONE")); }
	@Test void t014_08_to_27_completeFlowHidesKeysAndCalculatesCompanyResults() {
		Fixture f=create(); var api=f.student().api();
		assertThat(api.get(route(f)).json().get("questions")).isEmpty();
		start(f);
		var visible=api.get(route(f));
		assertThat(visible.header("Cache-Control")).isEqualTo("no-store");
		assertThat(visible.body()).doesNotContain("correctId","references","keyPoints","score100","gradeDetail","case-2");
		assertThat(visible.json().get("questions")).hasSize(10);
		for(var q:questions(f)) {
			Map<String,Object> body=new HashMap<>(); body.put("revision",0);
			if(q.type()==ExamQuestion.Type.CHOICE) { body.put("choiceId",q.correctId()); assertThat(q.correctId()).isNotEqualTo("correct"); }
			else if(q.type()==ExamQuestion.Type.OPEN) body.put("text","Explico la solución y una prueba técnica de un caso límite.");
			else body.put("code",q.references().getFirst());
			assertThat(api.put(route(f)+"/answers/"+q.id(),body).status()).isEqualTo(200);
		}
		assertThat(api.post(route(f)+"/submit",null).status()).isEqualTo(200);
		done(f);
		var result=results.forApplication(f.application()).orElseThrow();
		assertThat(result.score100()).isEqualByComparingTo("92.50"); assertThat(result.score20()).isEqualByComparingTo("18.50");
		assertThat(result.pendingPoints()).isZero(); assertThat(result.incomplete()).isFalse();
		assertThat(jdbc.queryForObject("SELECT status FROM application WHERE id=?",String.class,f.application())).isEqualTo("EXAM_DONE");
		assertThat(api.get(route(f)).body()).doesNotContain("score100","points","gradeDetail");
	}
	@Test void t014_11_revisionOwnershipAndClosedAnswers() {
		Fixture f=create(); start(f); var q=questions(f).getFirst(); var api=f.student().api();
		String path=route(f)+"/answers/"+q.id(); var body=Map.of("choiceId",q.correctId(),"revision",0);
		assertThat(api.put(path,body).status()).isEqualTo(200);
		assertThat(api.put(path,body).json().get("code").asString()).isEqualTo("stale-revision");
		var other=registerStudent().api(); assertThat(other.get(route(f)).status()).isEqualTo(404);
		assertThat(other.put(path,body).status()).isEqualTo(404);
		assertThat(api.writeWithToken("PUT",path,body,null).status()).isEqualTo(403);
		api.post(route(f)+"/submit",null);
		assertThat(api.put(path,Map.of("choiceId",q.correctId(),"revision",1)).status()).isEqualTo(409);
	}
	@Test void t014_13_expiredWriteCommitsTimeoutRatherThanRollingItBack() {
		Fixture f=create();start(f);clock.advance(Duration.ofMinutes(61));
		var q=questions(f).getFirst();
		assertThat(f.student().api().put(route(f)+"/answers/"+q.id(),Map.of("choiceId",q.correctId(),"revision",0)).status()).isEqualTo(409);
		assertThat(store.findAttempt(f.attempt()).orElseThrow().status()).isEqualTo(AttemptStatus.TIMED_OUT);
		assertThat(store.answer(f.attempt(),q.id()).orElseThrow().revision()).isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM application WHERE id=?",String.class,f.application())).isEqualTo("EXAM_DONE");
	}
	@Test void t014_14_15_screenEventsAreIdempotentAndSecondExitGradesSavedAnswers() {
		Fixture f=create();start(f);var api=f.student().api();
		assertThat(api.post(route(f)+"/events",Map.of("kind","CONNECTION_LOST","clientEventId",UUID.randomUUID(),"clientOccurredAt",clock.instant().toString())).json().get("rule").asString()).isEqualTo("NONE");
		var first=Map.of("kind","SCREEN_LEFT","clientEventId",UUID.randomUUID(),"clientOccurredAt",clock.instant().toString());
		assertThat(api.post(route(f)+"/events",first).json().get("rule").asString()).isEqualTo("WARNING");
		assertThat(api.post(route(f)+"/events",first).json().get("rule").asString()).isEqualTo("WARNING");
		assertThat(store.findAttempt(f.attempt()).orElseThrow().screenLeftCount()).isEqualTo(1);
		var second=Map.of("kind","SCREEN_LEFT","clientEventId",UUID.randomUUID(),"clientOccurredAt",clock.instant().toString());
		assertThat(api.post(route(f)+"/events",second).json().get("rule").asString()).isEqualTo("CLOSED");
		assertThat(api.post(route(f)+"/events",second).status()).isEqualTo(200);
		done(f);var result=results.forApplication(f.application()).orElseThrow();
		assertThat(result.closedReason()).isEqualTo("SCREEN_LEFT_TWICE");assertThat(result.incomplete()).isTrue();
		assertThat(store.events(f.attempt()).stream().filter(e->e.kind().equals("SCREEN_LEFT"))).hasSize(2);
		assertThat(result.toString()).doesNotContain("fraud","suspicion","ranking");
	}
	@Test void t014_19_platformFailureKeepsOpenPointsPendingAndEventuallyRequiresHumanReview() {
		Fixture f=create();start(f);grader.fail(true);
		var open=questions(f).stream().filter(q->q.type()==ExamQuestion.Type.OPEN).toList();
		for(var q:open) f.student().api().put(route(f)+"/answers/"+q.id(),Map.of("text","Respuesta técnica","revision",0));
		f.student().api().post(route(f)+"/submit",null);
		await(()->store.answers(f.attempt()).stream().filter(a->open.stream().anyMatch(q->q.id().equals(a.questionId())))
				.allMatch(a->a.points()==null&&a.nextRetryAt()!=null&&a.nextRetryAt().isBefore(clock.instant().plusSeconds(60))));
		assertThat(results.forApplication(f.application()).orElseThrow().pendingPoints()).isEqualByComparingTo("30");
		clock.advance(Duration.ofHours(25));grading.retryPending();
		await(()->store.answers(f.attempt()).stream().filter(a->open.stream().anyMatch(q->q.id().equals(a.questionId()))).allMatch(a->a.gradeStatus().equals("UNAVAILABLE")));
		assertThat(results.forApplication(f.application()).orElseThrow().gradingStatus()).isEqualTo("PROVISIONAL");
	}
	@Test void t014_12_runsOnlyVisibleCasesAndRefundsUnavailableInfrastructure() {
		Fixture f=create();start(f);var q=questions(f).stream().filter(v->v.type()==ExamQuestion.Type.CODE).findFirst().orElseThrow();
		var api=f.student().api();String path=route(f)+"/answers/"+q.id();
		api.put(path,Map.of("code",q.references().getFirst(),"revision",0));
		var response=api.post(path+"/run",null);assertThat(response.status()).isEqualTo(200);assertThat(response.json().get("cases")).hasSize(2);
		execution.unavailable(true);assertThat(api.post(path+"/run",null).status()).isEqualTo(503);
		assertThat(store.answer(f.attempt(),q.id()).orElseThrow().runCount()).isEqualTo(1);
	}
	@Test void t014_35_withdrawnAttemptClosesWithoutGrading() {
		Fixture f=create();start(f);
		assertThat(f.student().api().post("/api/v1/applications/"+f.application()+"/withdraw",null).status()).isEqualTo(200);
		assertThat(store.findAttempt(f.attempt()).orElseThrow().status()).isEqualTo(AttemptStatus.CLOSED_WITHDRAWN);
		assertThat(store.findAttempt(f.attempt()).orElseThrow().score100()).isNull();
	}
	@Test void executionLimitAndSaveRateAreEnforcedPerPerson() {
		Fixture f=create(); start(f); var api=f.student().api();
		var q=questions(f).stream().filter(v->v.type()==ExamQuestion.Type.CODE).findFirst().orElseThrow();
		String path=route(f)+"/answers/"+q.id();
		assertThat(api.put(path,Map.of("code",q.references().getFirst(),"revision",0)).status()).isEqualTo(200);
		for(int i=0;i<15;i++) assertThat(api.post(path+"/run",null).status()).isEqualTo(200);
		assertThat(api.post(path+"/run",null).status()).isEqualTo(429);
		assertThat(store.answer(f.attempt(),q.id()).orElseThrow().runCount()).isEqualTo(15);
		for(int i=1;i<60;i++) assertThat(api.put(path,Map.of("code",q.references().getFirst(),"revision",i)).status()).isEqualTo(200);
		assertThat(api.put(path,Map.of("code",q.references().getFirst(),"revision",60)).status()).isEqualTo(429);
		clock.advance(Duration.ofSeconds(61));
		assertThat(api.put(path,Map.of("code",q.references().getFirst(),"revision",60)).status()).isEqualTo(200);
	}
	@Test void authorFailureFallsBackAndConcurrentRequestsKeepOneSet() throws Exception {
		author.fail(true); author.delay(Duration.ofMillis(50));
		var student=registerStudent(); UUID application=application(student,true);
		UUID vacancy=jdbc.queryForObject("SELECT vacancy_id FROM application WHERE id=?",UUID.class,application);
		try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
			var calls=new ArrayList<java.util.concurrent.Future<com.talenti.exam.persistence.ExamStore.SetRow>>();
			for(int i=0;i<6;i++) calls.add(pool.submit(()->authoring.ensure(vacancy)));
			var ids=new HashSet<UUID>(); for(var call:calls) ids.add(call.get().id());
			assertThat(ids).hasSize(1);
		}
		await(()->store.activeSet(vacancy).orElseThrow().status().equals("READY"));
		var set=store.activeSet(vacancy).orElseThrow();
		assertThat(set.source()).isEqualTo("BANK"); assertThat(store.versions(set.id())).hasSize(3);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM exam_set WHERE vacancy_id=?",Integer.class,vacancy)).isEqualTo(1);
	}
	@Test void t014_02_34_unsupportedAndRoleSecurityDoNotModifyApplication() {
		var student=registerStudent();UUID application=application(student,false);String path="/api/v1/applications/"+application+"/exam";
		var body=Map.of("noticeAccepted",true,"noticeVersion","v1");
		assertThat(student.api().post(path,body).json().get("code").asString()).isEqualTo("exam-not-supported");
		assertThat(jdbc.queryForObject("SELECT status FROM application WHERE id=?",String.class,application)).isEqualTo("SUBMITTED");
		assertThat(client().get("/api/v1/exam-attempts/"+UUID.randomUUID()).status()).isEqualTo(401);
		assertThat(student.api().get("/api/v1/admin/vacancies/"+BACKEND_JAVA+"/exam-set").status()).isEqualTo(403);
	}
}
