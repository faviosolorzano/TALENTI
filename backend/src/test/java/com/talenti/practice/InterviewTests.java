package com.talenti.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.talenti.practice.service.DoraService;
import com.talenti.practice.service.EventSink;
import com.talenti.practice.service.UsageMeter;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;
import com.talenti.support.SseClient;

import tools.jackson.databind.JsonNode;

/** Entrevista evaluada de una postulación (012-23 a 012-28, 012-35, 012-36). */
class InterviewTests extends PracticeIntegrationTest {

	private static final String NOTICE = "v1";

	@Autowired
	private InterviewResults results;

	@Autowired
	private DoraService dora;

	@Autowired
	private UsageMeter usage;

	private static String interview(String applicationId) {
		return "/api/v1/applications/" + applicationId + "/interview";
	}

	private Response startInterview(ApiClient api, String applicationId) {
		return api.post(interview(applicationId), body("noticeAccepted", true, "noticeVersion", NOTICE));
	}

	private String createInterview(ApiClient api, String applicationId) {
		Response response = startInterview(api, applicationId);
		assertThat(response.status()).as("entrevista: %s", response.body()).isEqualTo(201);
		return response.json().get("id").asString();
	}

	private void setStatus(String applicationId, String status) {
		jdbc.update("UPDATE application SET status = ?, withdrawn_at = CASE WHEN ? = 'WITHDRAWN' THEN now() END "
				+ "WHERE id = ?::uuid", status, status, applicationId);
	}

	/** Responde todas las preguntas de la entrevista por texto; devuelve el último turno. */
	private SseClient.Result answerAll(ApiClient api, String session, int count) {
		SseClient.Result last = null;
		for (int position = 1; position <= count; position++) {
			last = textTurn(api, session, position, "Respuesta inventada " + position
					+ ": en mi proyecto usé Java con Spring Boot y separé el código en servicios y controladores.");
			assertThat(last.status()).isEqualTo(200);
		}
		return last;
	}

	@Test
	@DisplayName("012-23: con aviso válido sobre una postulación SUBMITTED crea la entrevista, guarda el aviso y la pasa a INTERVIEW_PENDING con su evento")
	void t012_23_createInterview() {
		RegisteredStudent student = studentWithProfile();
		String application = apply(student.api());

		Response response = startInterview(student.api(), application);

		assertThat(response.status()).as(response.body()).isEqualTo(201);
		JsonNode session = response.json();
		assertThat(session.get("mode").asString()).isEqualTo("INTERVIEW");
		assertThat(session.get("applicationId").asString()).isEqualTo(application);
		assertThat(session.get("questionCount").asInt()).isEqualTo(5);
		assertThat(applicationStatus(application)).isEqualTo("INTERVIEW_PENDING");
		assertThat(count("SELECT count(*) FROM application_stage_event WHERE application_id = ?::uuid "
				+ "AND from_status = 'SUBMITTED' AND to_status = 'INTERVIEW_PENDING'", application)).isEqualTo(1);
		assertThat(jdbc.queryForMap("SELECT notice_version, notice_accepted_at FROM practice_session WHERE id = ?::uuid",
				id(session)).get("notice_version")).isEqualTo("v1");
		assertThat(jdbc.queryForList("SELECT kind FROM interview_event WHERE session_id = ?::uuid", String.class,
				id(session))).containsExactly("NOTICE_ACCEPTED");
		assertThat(jdbc.queryForList("SELECT basis_ref FROM practice_question WHERE session_id = ?::uuid", String.class,
				id(session))).allMatch(ref -> ref.startsWith("requirement:") || ref.equals("general"));
		String plan = llm.journal().stream().filter(r -> r.startsWith("PLAN purpose=INTERVIEW")).findFirst().orElseThrow();
		assertThat(plan).contains("requirement:java").doesNotContain("project:").doesNotContain("Gestor de tareas");
	}

	@Test
	@DisplayName("012-23: postulación ajena 404, sin aviso 422, versión vieja 409, otro estado 409 y otra sesión abierta 409")
	void t012_23_errors() {
		RegisteredStudent student = studentWithProfile();
		String application = apply(student.api());
		RegisteredStudent intruder = studentWithProfile();

		assertThat(startInterview(intruder.api(), application).status()).isEqualTo(404);
		assertThat(startInterview(student.api(), UUID.randomUUID().toString()).status()).isEqualTo(404);
		assertThat(errors(student.api().post(interview(application), body("noticeAccepted", false, "noticeVersion",
				NOTICE)))).containsExactly("noticeAccepted:must-be-accepted");
		Response old = student.api().post(interview(application), body("noticeAccepted", true, "noticeVersion", "v0"));
		assertThat(old.status()).isEqualTo(409);
		assertThat(old.json().get("code").asString()).isEqualTo("notice-version-outdated");
		String practice = id(createPractice(student.api(), 2));
		Response busy = startInterview(student.api(), application);
		assertThat(busy.status()).isEqualTo(409);
		assertThat(busy.json().get("code").asString()).isEqualTo("session-open");
		ok(student.api().post(DORA + practice + "/finish", null));
		setStatus(application, "UNDER_REVIEW");
		Response wrong = startInterview(student.api(), application);
		assertThat(wrong.status()).isEqualTo(409);
		assertThat(wrong.json().get("code").asString()).isEqualTo("interview-not-allowed");
		assertThat(applicationStatus(application)).isEqualTo("UNDER_REVIEW");
	}

	@Test
	@DisplayName("012-35: la entrevista empieza desde EXAM_DONE (201) y no desde EXAM_PENDING (409, la postulación no cambia)")
	void t012_35_examStates() {
		RegisteredStudent first = studentWithProfile();
		String done = apply(first.api());
		setStatus(done, "EXAM_DONE");
		createInterview(first.api(), done);
		assertThat(applicationStatus(done)).isEqualTo("INTERVIEW_PENDING");
		assertThat(count("SELECT count(*) FROM application_stage_event WHERE application_id = ?::uuid "
				+ "AND from_status = 'EXAM_DONE' AND to_status = 'INTERVIEW_PENDING'", done)).isEqualTo(1);

		RegisteredStudent second = studentWithProfile();
		String pending = apply(second.api());
		setStatus(pending, "EXAM_PENDING");
		Response refused = startInterview(second.api(), pending);
		assertThat(refused.status()).isEqualTo(409);
		assertThat(applicationStatus(pending)).isEqualTo("EXAM_PENDING");
		assertThat(count("SELECT count(*) FROM practice_session WHERE application_id = ?::uuid", pending)).isZero();
	}

	@Test
	@DisplayName("012-36: se puede retirar desde SUBMITTED, EXAM_PENDING, EXAM_DONE, INTERVIEW_PENDING e INTERVIEW_DONE, con su evento")
	void t012_36_withdrawFromEveryAllowedStage() {
		for (String stage : List.of("SUBMITTED", "EXAM_PENDING", "EXAM_DONE", "INTERVIEW_PENDING", "INTERVIEW_DONE")) {
			RegisteredStudent student = studentWithProfile();
			String application = apply(student.api());
			setStatus(application, stage);

			Response withdrawn = student.api().post("/api/v1/applications/" + application + "/withdraw", null);

			assertThat(withdrawn.status()).as(stage).isEqualTo(200);
			assertThat(applicationStatus(application)).isEqualTo("WITHDRAWN");
			assertThat(count("SELECT count(*) FROM application_stage_event WHERE application_id = ?::uuid "
					+ "AND from_status = ? AND to_status = 'WITHDRAWN'", application, stage)).as(stage).isEqualTo(1);
		}
	}

	@Test
	@DisplayName("012-36 y sección 4: retirar la postulación con la entrevista en curso deja la sesión ABANDONED")
	void t012_36_withdrawAbandonsTheInterview() {
		RegisteredStudent student = studentWithProfile();
		String application = apply(student.api());
		String session = createInterview(student.api(), application);
		start(student.api(), session);
		textTurn(student.api(), session, 1, "Respuesta inventada antes de retirar.");

		assertThat(student.api().post("/api/v1/applications/" + application + "/withdraw", null).status()).isEqualTo(200);

		assertThat(sessionStatus(session)).isEqualTo("ABANDONED");
		assertThat(jdbc.queryForList("SELECT kind FROM interview_event WHERE session_id = ?::uuid", String.class, session))
				.contains("ABANDONED");
		assertThat(textTurn(student.api(), session, 2, "Ya no debería poder responder.").status()).isEqualTo(409);
	}

	@Test
	@DisplayName("012-25, 012-26 y 012-27: al terminar, cada respuesta se evalúa por criterios, la postulación pasa a INTERVIEW_DONE y quedan los hechos")
	void t012_25_completedInterviewIsEvaluated() {
		RegisteredStudent student = studentWithProfile();
		String application = apply(student.api());
		String session = createInterview(student.api(), application);
		SseClient.Result started = start(student.api(), session);
		assertThat(started.first("reply.text").data().get("text").asString()).contains("entrevista evaluada")
				.contains("Soy DORA, una asistente de inteligencia artificial");

		SseClient.Result last = answerAll(student.api(), session, 5);

		assertThat(last.first("question").data().get("closing").asBoolean()).isTrue();
		assertThat(last.first("turn.done").data().get("sessionStatus").asString()).isEqualTo("COMPLETED");
		assertThat(applicationStatus(application)).isEqualTo("INTERVIEW_DONE");
		await("evaluación de las 5 respuestas", () -> count("SELECT count(*) FROM practice_feedback f JOIN practice_turn t "
				+ "ON t.id = f.turn_id JOIN practice_question q ON q.id = t.question_id WHERE q.session_id = ?::uuid "
				+ "AND f.status = 'READY'", session) == 5);
		await("consumo de la evaluación", () -> count("SELECT count(*) FROM ai_usage_event WHERE user_id = ? "
				+ "AND function = 'INTERVIEW_EVALUATION'", student.id()) == 5);

		List<InterviewResults.Attempt> attempts = results.forApplication(UUID.fromString(application));
		assertThat(attempts).hasSize(1);
		InterviewResults.Attempt attempt = attempts.get(0);
		assertThat(attempt.status()).isEqualTo("COMPLETED");
		assertThat(attempt.answers()).hasSize(5).allSatisfy(answer -> {
			assertThat(answer.evaluationStatus()).isEqualTo("READY");
			assertThat(answer.criteria()).extracting(InterviewResults.CriterionResult::criterion)
					.containsExactly("TECHNICAL_ACCURACY", "CLARITY", "STRUCTURE", "EXAMPLES");
			answer.criteria().forEach(c -> c.quotes().forEach(q -> assertThat(answer.transcriptFinal()).contains(q)));
		});
		assertThat(attempt.unanswered()).isZero();
		assertThat(attempt.summary()).hasSize(4).allSatisfy(s -> assertThat(s.evaluated()).isEqualTo(5));
		assertThat(attempt.generatedBy()).isEqualTo("AI");
		assertThat(attempt.versions().rubricVersion()).isEqualTo("rubric-v1");
		List<String> kinds = attempt.events().stream().map(InterviewResults.Event::kind).toList();
		assertThat(kinds).contains("NOTICE_ACCEPTED", "STARTED", "ANSWERED", "FINISHED");
		assertThat(kinds.stream().filter("ANSWERED"::equals).count()).isEqualTo(5);
		assertThat(jdbc.queryForList("SELECT data::text FROM interview_event WHERE session_id = ?::uuid", String.class,
				session)).noneMatch(data -> data.contains("Respuesta inventada"));
		assertThat(InterviewResults.Attempt.class.getRecordComponents()).extracting(c -> c.getName().toLowerCase())
				.noneMatch(name -> name.contains("recommend") || name.contains("rank") || name.contains("hire"));

		JsonNode view = ok(student.api().get(interview(application)));
		assertThat(view.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(view.get("questions")).hasSize(5);
		assertThat(view.get("questions").get(0).get("transcriptFinal").asString()).startsWith("Respuesta inventada 1");
		assertThat(view.toString()).as("el estudiante no ve sus niveles (duda 7)").doesNotContain("level")
				.doesNotContain("criteria");
	}

	@Test
	@DisplayName("012-27: GET de la entrevista da 204 si no hubo; InterviewResults nunca devuelve prácticas")
	void t012_27_noInterviewAndNoPractice() {
		RegisteredStudent student = studentWithProfile();
		String application = apply(student.api());
		String practice = id(createPractice(student.api(), 1));
		start(student.api(), practice);
		textTurn(student.api(), practice, 1, "Respuesta de práctica privada.");

		assertThat(student.api().get(interview(application)).status()).isEqualTo(204);
		assertThat(results.forApplication(UUID.fromString(application))).isEmpty();
		assertThat(student.api().get(interview(UUID.randomUUID().toString())).status()).isEqualTo(404);
	}

	@Test
	@DisplayName("012-13 y 012-26: en la entrevista se corrige la transcripción (queda la original) pero no se repite una respuesta")
	void t012_13_interviewCorrectionWithoutRepeat() {
		RegisteredStudent student = studentWithProfile();
		String application = apply(student.api());
		String session = createInterview(student.api(), application);
		start(student.api(), session);
		String turn = turnId(textTurn(student.api(), session, 1, "Texto original de la entrevista."));

		Response patched = student.api().writeWithToken("PATCH", DORA + session + "/turns/" + turn,
				body("transcript", "Texto corregido de la entrevista."), student.api().cookie("XSRF-TOKEN"));
		SseClient.Result repeat = textTurn(student.api(), session, 1, "Intento repetir.");

		assertThat(patched.status()).isEqualTo(200);
		assertThat(patched.json().get("transcriptRaw").asString()).isEqualTo("Texto original de la entrevista.");
		assertThat(repeat.status()).isEqualTo(409);
		assertThat(jdbc.queryForList("SELECT kind FROM interview_event WHERE session_id = ?::uuid", String.class, session))
				.contains("TRANSCRIPT_CORRECTED");
		assertThat(feedbackStatus(turn)).as("la entrevista se evalúa al terminar, no por respuesta").isNull();
	}

	@Test
	@DisplayName("012-26: tras una falla de plataforma la postulación sigue en INTERVIEW_PENDING y hay un nuevo intento (máximo 2)")
	void t012_26_retryAfterPlatformFailure() {
		RegisteredStudent student = studentWithProfile();
		String application = apply(student.api());
		String first = createInterview(student.api(), application);
		start(student.api(), first);
		lifecycle.recoverAfterRestart();

		assertThat(sessionStatus(first)).isEqualTo("FAILED");
		assertThat(applicationStatus(application)).isEqualTo("INTERVIEW_PENDING");
		JsonNode view = ok(student.api().get(interview(application)));
		assertThat(view.get("canRetry").asBoolean()).isTrue();
		assertThat(jdbc.queryForList("SELECT kind FROM interview_event WHERE session_id = ?::uuid", String.class, first))
				.contains("PLATFORM_FAILURE");

		Response second = startInterview(student.api(), application);
		assertThat(second.status()).isEqualTo(201);
		assertThat(second.json().get("attempt").asInt()).isEqualTo(2);
		lifecycle.recoverAfterRestart();
		Response third = startInterview(student.api(), application);
		assertThat(third.status()).isEqualTo(409);
		assertThat(third.json().get("code").asString()).isEqualTo("interview-attempts-exhausted");
		assertThat(results.forApplication(UUID.fromString(application))).hasSize(2);
	}

	@Test
	@DisplayName("012-26: si la persona se desconecta a mitad del flujo, el turno queda guardado y se anota la desconexión")
	void t012_26_disconnectionIsRecorded() {
		RegisteredStudent student = studentWithProfile();
		String application = apply(student.api());
		String session = createInterview(student.api(), application);
		start(student.api(), session);
		List<String> sent = new ArrayList<>();
		EventSink gone = new EventSink() {

			@Override
			public boolean send(String event, Map<String, Object> data) {
				sent.add(event);
				return false;
			}

			@Override
			public boolean disconnected() {
				return true;
			}

			@Override
			public void complete() {
			}

		};

		dora.turn(student.id(), UUID.fromString(session), new DoraService.TurnCommand(null,
				"Respuesta que se guarda aunque se corte la red.", 1, UUID.randomUUID()), System.nanoTime()).run(gone);

		assertThat(count("SELECT count(*) FROM practice_turn t JOIN practice_question q ON q.id = t.question_id "
				+ "WHERE q.session_id = ?::uuid", session)).isEqualTo(1);
		assertThat(jdbc.queryForList("SELECT kind FROM interview_event WHERE session_id = ?::uuid", String.class, session))
				.contains("DISCONNECTED");
	}

	@Test
	@DisplayName("012-28: la entrevista no usa el cupo personal de práctica; con el tope de gasto agotado da 503 y la postulación no cambia")
	void t012_28_interviewOutsideThePersonalQuota() {
		RegisteredStudent student = studentWithProfile();
		OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		for (int i = 0; i < 20; i++) {
			jdbc.update("""
					INSERT INTO ai_usage_event (id, occurred_at, user_id, function, level, adapter, outcome, read_id)
					VALUES (?, ?, ?, 'PRACTICE_SESSION', 'BASIC', 'simulated', 'SUCCESS', ?)""", UUID.randomUUID(), now,
					student.id(), UUID.randomUUID());
		}
		String application = apply(student.api());
		String session = createInterview(student.api(), application);
		ok(student.api().post(DORA + session + "/finish", null));

		RegisteredStudent other = studentWithProfile();
		String blocked = apply(other.api());
		UUID costly = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO ai_usage_event (id, occurred_at, function, level, adapter, outcome, read_id, estimated_cost_micros)
				VALUES (?, ?, 'DORA_DIALOG', 'ENHANCED', 'prueba', 'SUCCESS', ?, 999000000)""", UUID.randomUUID(), now,
				costly);
		usage.refreshSpend();
		try {
			Response unavailable = startInterview(other.api(), blocked);
			assertThat(unavailable.status()).isEqualTo(503);
			assertThat(unavailable.json().get("code").asString()).isEqualTo("interview-unavailable");
			assertThat(applicationStatus(blocked)).isEqualTo("SUBMITTED");
		}
		finally {
			jdbc.update("DELETE FROM ai_usage_event WHERE read_id = ?", costly);
			usage.refreshSpend();
		}
	}

	@Test
	@DisplayName("012-24: DORA dice el primer nombre (conversación y voz); nunca apellidos ni contacto; la evaluación no lo recibe")
	void t012_24_firstNameGoesToConversationButNeverToEvaluation() {
		String first = letters("Zq");
		String last = letters("Ap");
		RegisteredStudent student = studentWithProfile(first + " Segundo", last + " Otro");
		String application = apply(student.api());
		String session = createInterview(student.api(), application);

		SseClient.Result started = start(student.api(), session);
		answerAll(student.api(), session, 5);
		await("evaluación", () -> llm.journal().stream().filter(r -> r.startsWith("EVALUATE")).count() == 5);

		String practice = id(createPractice(student.api(), 1));
		start(student.api(), practice);
		String turn = turnId(textTurn(student.api(), practice, 1, "Respuesta inventada de práctica."));
		awaitFeedbackDone(turn);

		assertThat(started.first("reply.text").data().get("text").asString()).startsWith("Hola, " + first + ".");
		assertThat(tts.journal()).anyMatch(text -> text.contains(first));
		assertThat(llm.journal().stream().filter(r -> r.startsWith("BRIDGE")))
				.allMatch(r -> r.contains("name=" + first + " "));
		List<String> evaluations = llm.journal().stream().filter(r -> r.startsWith("EVALUATE")).toList();
		assertThat(evaluations).hasSize(6).noneMatch(r -> r.contains(first));
		List<String> everything = new ArrayList<>(llm.journal());
		everything.addAll(tts.journal());
		assertThat(everything).noneMatch(r -> r.contains(last) || r.contains("Segundo") || r.contains(student.email())
				|| r.contains("912345678"));

		RegisteredStudent nameless = studentWithProfile(null, null);
		String plain = id(createPractice(nameless.api(), 1));
		assertThat(start(nameless.api(), plain).first("reply.text").data().get("text").asString())
				.startsWith("Hola. Soy DORA");
	}

}
