package com.talenti.exam;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.talenti.TalentiBackendApplication;
import com.talenti.TestcontainersConfiguration;
import com.talenti.exam.adapter.SimulatedOpenAnswerGrader;
import com.talenti.exam.domain.ExamQuestion;
import com.talenti.exam.persistence.ExamStore;
import com.talenti.exam.service.ExamAuthoringService;
import com.talenti.support.ApiClient;
import com.talenti.support.MutableClock;

/** Dos arranques reales de Spring, con la misma base efímera y sin conservar objetos del primer servidor. */
class ExamRestartTests {
	@TestConfiguration(proxyBeanMethods=false)
	static class RestartClock {
		@Bean @Primary MutableClock restartClock(@Value("${talenti.test.exam.instant}") String instant) {
			return new MutableClock(Instant.parse(instant));
		}
	}
	private ConfigurableApplicationContext boot(String url,Instant now) {
		return new SpringApplicationBuilder(TalentiBackendApplication.class,RestartClock.class).run(
				"--server.port=0","--spring.datasource.url="+url,
				"--spring.datasource.username="+TestcontainersConfiguration.APP_USER,
				"--spring.datasource.password="+TestcontainersConfiguration.APP_PASSWORD,
				"--talenti.demo-data.enabled=true","--talenti.exam.runner.mode=simulated",
				"--talenti.ai.exam.enabled=false","--talenti.security.secure-cookies=false",
				"--talenti.exam.sweep-interval=PT1H","--talenti.exam.retry-interval=PT1H",
				"--talenti.test.exam.instant="+now,"--spring.main.banner-mode=off","--logging.level.root=WARN");
	}
	private static ApiClient client(ConfigurableApplicationContext context) {
		return new ApiClient(((WebServerApplicationContext)context).getWebServer().getPort());
	}
	private static void await(BooleanSupplier condition) throws InterruptedException {
		long end=System.nanoTime()+Duration.ofSeconds(20).toNanos();
		while(!condition.getAsBoolean()) { if(System.nanoTime()>end) throw new AssertionError("No se recuperó el trabajo persistido");Thread.sleep(25); }
	}
	private static UUID create(ApiClient api,UUID application) throws InterruptedException {
		final UUID[] id={null};
		await(()->{
			var response=api.post("/api/v1/applications/"+application+"/exam",Map.of("noticeAccepted",true,"noticeVersion","v1"));
			assertThat(response.status()).as(response.body()).isIn(201,202);
			if(response.status()==201) id[0]=UUID.fromString(response.json().get("id").asString());
			return id[0]!=null;
		});
		return id[0];
	}
	@Test void t014_13_restartKeepsDeadlineAnswersAndScreenEventsAndRecoversExpiredGradingLease() throws Exception {
		Instant initial=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);String email="reinicio."+UUID.randomUUID()+"@ejemplo.test";
		String password="Aa1"+UUID.randomUUID().toString().replace("-","");
		UUID user,active,submitted,activeQuestion,pendingAnswer; Instant deadline;long seed;
		try(var database=TestcontainersConfiguration.newContainer()) {
			database.start();
			try(var first=boot(database.getJdbcUrl(),initial)) {
				var api=client(first);var jdbc=first.getBean(JdbcTemplate.class);var store=first.getBean(ExamStore.class);
				var registration=api.post("/api/v1/auth/register",Map.of("email",email,"password",password,"legalAccepted",true,"legalVersion","v1"));
				assertThat(registration.status()).as(registration.body()).isEqualTo(201);user=UUID.fromString(registration.json().get("id").asString());
				UUID vacancy=jdbc.queryForObject("SELECT id FROM vacancy WHERE title='Practicante Backend Java'",UUID.class);
				UUID otherVacancy=jdbc.queryForObject("SELECT id FROM vacancy WHERE id<>? LIMIT 1",UUID.class,vacancy);
				UUID a=UUID.randomUUID(),b=UUID.randomUUID();
				jdbc.update("INSERT INTO application(id,user_id,vacancy_id,status,submitted_at) VALUES (?,?,?,'SUBMITTED',now())",a,user,vacancy);
				jdbc.update("INSERT INTO application(id,user_id,vacancy_id,status,submitted_at) VALUES (?,?,?,'SUBMITTED',now())",b,user,otherVacancy);
				active=create(api,a);submitted=create(api,b);
				for(UUID id:List.of(active,submitted)) assertThat(api.post("/api/v1/exam-attempts/"+id+"/start",null).status()).isEqualTo(200);
				var attempt=store.findAttempt(active).orElseThrow();deadline=attempt.deadlineAt();seed=attempt.shuffleSeed();
				activeQuestion=store.questions(attempt.versionId()).stream().filter(q->q.type()==ExamQuestion.Type.OPEN).findFirst().orElseThrow().id();
				assertThat(api.put("/api/v1/exam-attempts/"+active+"/answers/"+activeQuestion,Map.of("text","Respuesta conservada","revision",0)).status()).isEqualTo(200);
				assertThat(api.post("/api/v1/exam-attempts/"+active+"/events",Map.of("kind","SCREEN_LEFT","clientEventId",UUID.randomUUID(),"clientOccurredAt",initial.toString())).status()).isEqualTo(200);
				var q=store.questions(store.findAttempt(submitted).orElseThrow().versionId()).stream().filter(v->v.type()==ExamQuestion.Type.OPEN).findFirst().orElseThrow();
				assertThat(api.put("/api/v1/exam-attempts/"+submitted+"/answers/"+q.id(),Map.of("text","Respuesta técnica pendiente","revision",0)).status()).isEqualTo(200);
				first.getBean(SimulatedOpenAnswerGrader.class).fail(true);
				assertThat(api.post("/api/v1/exam-attempts/"+submitted+"/submit",null).status()).isEqualTo(200);
				pendingAnswer=store.answer(submitted,q.id()).orElseThrow().id();
				await(()->store.answer(submitted,q.id()).orElseThrow().gradeDetail().contains("PLATFORM_RETRY")
						&&store.answers(submitted).stream().filter(r->r.gradeStatus().equals("PENDING")).count()==1);
				// Simula la concesión persistida de una corrección interrumpida antes de cerrar el proceso.
				jdbc.update("UPDATE exam_answer SET next_retry_at=? WHERE id=?",java.time.OffsetDateTime.ofInstant(initial.plusSeconds(300),ZoneOffset.UTC),pendingAnswer);
			}
			try(var second=boot(database.getJdbcUrl(),initial.plusSeconds(360))) {
				var store=second.getBean(ExamStore.class);
				await(()->store.findAttempt(submitted).orElseThrow().gradingStatus().equals("DONE"));
				assertThat(store.findAttempt(submitted).orElseThrow().score100()).isEqualByComparingTo("7.50");
				var restored=store.findAttempt(active).orElseThrow();
				assertThat(restored.deadlineAt()).isEqualTo(deadline);assertThat(restored.shuffleSeed()).isEqualTo(seed);assertThat(restored.screenLeftCount()).isEqualTo(1);
				assertThat(store.answer(active,activeQuestion).orElseThrow().text()).isEqualTo("Respuesta conservada");
				assertThat(store.answer(active,activeQuestion).orElseThrow().revision()).isEqualTo(1);
				var api=client(second);assertThat(api.post("/api/v1/auth/login",Map.of("email",email,"password",password)).status()).isEqualTo(200);
				assertThat(api.get("/api/v1/exam-attempts/"+active).json().get("remainingSeconds").asLong()).isEqualTo(3240);
				second.getBean(MutableClock.class).advance(Duration.ofMinutes(61));
				assertThat(api.get("/api/v1/exam-attempts/"+active).json().get("status").asString()).isEqualTo("TIMED_OUT");
				assertThat(second.getBean(JdbcTemplate.class).queryForObject("SELECT status FROM application WHERE id=?",String.class,restored.applicationId())).isEqualTo("EXAM_DONE");
				await(()->store.findAttempt(active).orElseThrow().gradingStatus().equals("DONE"));
			}
		}
	}
}
