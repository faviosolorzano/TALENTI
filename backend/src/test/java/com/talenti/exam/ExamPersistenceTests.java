package com.talenti.exam;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.talenti.application.ApplicationStages;
import com.talenti.exam.domain.*;
import com.talenti.exam.domain.ExamQuestion.*;
import com.talenti.exam.persistence.ExamStore;
import com.talenti.matching.MatchingIntegrationTest;

/** PostgreSQL real con migración V11; no invoca IA ni ejecuta código del estudiante. */
class ExamPersistenceTests extends MatchingIntegrationTest {
	@Autowired ExamStore store;
	@Autowired ApplicationStages stages;
	@Autowired PlatformTransactionManager manager;
	private record Fixture(UUID user,UUID application,UUID set,UUID version,List<ExamQuestion> questions) { }
	private Fixture fixture() {
		UUID user=registerStudent().id(), application=UUID.randomUUID(), vacancy=UUID.fromString(BACKEND_JAVA);
		jdbc.update("INSERT INTO application(id,user_id,vacancy_id,status,submitted_at) VALUES (?,?,?,'SUBMITTED',now())",application,user,vacancy);
		// Usa una vacante propia para no compartir el conjunto entre pruebas.
		UUID ownVacancy=UUID.randomUUID();
		insertVacancyWithJava("Vacante examen "+ownVacancy,java.time.OffsetDateTime.now(),"OPEN",ownVacancy,true);
		jdbc.update("UPDATE application SET vacancy_id=? WHERE id=?",ownVacancy,application);
		var plan=Blueprint.create(List.of(new Blueprint.Technology("java","Java",true,2)),Map.of("java",Language.JAVA),
				new BigDecimal("6.25"),BigDecimal.TEN,new BigDecimal("15"));
		UUID set=store.insertSet(ownVacancy,plan,clock.instant()).orElseThrow(),version=UUID.randomUUID();
		List<ExamQuestion> questions=plan.slots().stream().map(s->new ExamQuestion(UUID.randomUUID(),s.position(),s.type(),
				s.skillSlug(),s.difficulty(),s.points(),"Pregunta técnica",null,null,null,null,s.language(),s.kind(),null,null,null,false,Source.BANK)).toList();
		store.setStatus(set,"VERIFYING",null);
		new TransactionTemplate(manager).executeWithoutResult(tx->{
			store.insertVersion(version,set,"A",questions,new ExamVerifier.Report(true,Map.of(),ExamVerifier.VERSION));
			store.ready(set,"BANK","simulated","v1","v1",clock.instant());
		});
		return new Fixture(user,application,set,version,questions);
	}
	@Test void t014_06_activeSetIsUniqueAndQuestionsRoundTrip() {
		Fixture f=fixture(); var set=store.findSet(f.set()).orElseThrow();
		assertThat(store.insertSet(set.vacancyId(),set.blueprint(),clock.instant())).isEmpty();
		assertThat(store.questions(f.version())).isEqualTo(f.questions());
		assertThat(store.activeSet(set.vacancyId()).orElseThrow().status()).isEqualTo("READY");
	}
	@Test void t014_08_stageChangesCheckOwnerAndKeepEvents() {
		Fixture f=fixture();
		assertThat(stages.startExam(f.application(),UUID.randomUUID())).isFalse();
		assertThat(stages.startExam(f.application(),f.user())).isTrue();
		assertThat(stages.startExam(f.application(),f.user())).isFalse();
		assertThat(stages.completeExam(f.application(),f.user())).isTrue();
		assertThat(stages.findOwned(f.application(),f.user()).orElseThrow().stage()).isEqualTo(ApplicationStages.Stage.EXAM_DONE);
		assertThat(jdbc.queryForList("SELECT to_status FROM application_stage_event WHERE application_id=? ORDER BY occurred_at,id",String.class,f.application()))
				.containsExactlyInAnyOrder("EXAM_PENDING","EXAM_DONE");
	}
	@Test void t014_07_usedVersionCannotBeEditedOrUnlocked() {
		Fixture f=fixture();
		new TransactionTemplate(manager).executeWithoutResult(tx->store.insertAttempt(f.application(),f.user(),f.version(),42,"v1",clock.instant()));
		assertThatThrownBy(()->jdbc.update("UPDATE exam_question SET prompt='Otro' WHERE id=?",f.questions().getFirst().id())).isInstanceOf(DataAccessException.class);
		assertThatThrownBy(()->jdbc.update("UPDATE exam_version SET locked=false WHERE id=?",f.version())).isInstanceOf(DataAccessException.class);
		assertThatThrownBy(()->jdbc.update("DELETE FROM exam_question WHERE id=?",f.questions().getFirst().id())).isInstanceOf(DataAccessException.class);
		assertThatThrownBy(()->jdbc.update("INSERT INTO exam_question(id,version_id,slot,type,skill_slug,difficulty,points,prompt,content,source) SELECT ?,version_id,slot,type,skill_slug,difficulty,points,prompt,content,source FROM exam_question WHERE id=?",UUID.randomUUID(),f.questions().getFirst().id()))
				.isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("exam-version-locked");
	}
	@Test void t014_11_concurrentAnswerRevisionOnlySavesOnce() throws Exception {
		Fixture f=fixture();
		var attempt=new TransactionTemplate(manager).execute(tx->store.insertAttempt(f.application(),f.user(),f.version(),42,"v1",clock.instant()));
		UUID q=f.questions().getFirst().id();
		try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
			Callable<Boolean> write=()->new TransactionTemplate(manager).execute(tx->{
				store.ownedAttempt(attempt.id(),f.user(),true).orElseThrow();
				return store.saveAnswer(attempt.id(),q,0,"a",null,null,clock.instant());
			});
			var a=pool.submit(write); var b=pool.submit(write);
			assertThat(List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
		}
		assertThat(store.answer(attempt.id(),q).orElseThrow().revision()).isEqualTo(1);
		assertThat(store.ownedAttempt(attempt.id(),UUID.randomUUID(),false)).isEmpty();
	}
	@Test void t014_14_eventsAreIdempotentAndAccountDeletionCascades() {
		Fixture f=fixture();
		var attempt=new TransactionTemplate(manager).execute(tx->store.insertAttempt(f.application(),f.user(),f.version(),42,"v1",clock.instant()));
		UUID event=UUID.randomUUID();
		assertThat(store.event(attempt.id(),"SCREEN_LEFT",event,clock.instant(),clock.instant(),Map.of())).isTrue();
		assertThat(store.event(attempt.id(),"SCREEN_LEFT",event,clock.instant(),clock.instant(),Map.of())).isFalse();
		assertThat(store.events(attempt.id())).hasSize(1);
		jdbc.update("DELETE FROM app_user WHERE id=?",f.user());
		assertThat(store.findAttempt(attempt.id())).isEmpty(); assertThat(store.answers(attempt.id())).isEmpty();
		assertThat(store.events(attempt.id())).isEmpty();
	}
}
