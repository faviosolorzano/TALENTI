package com.talenti.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.practice.adapter.Simulation;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/** Opciones, creación, planificación, historial y cupo de la práctica (012-01 a 012-04, 012-21, 012-29). */
class PracticeSessionTests extends PracticeIntegrationTest {

	@Test
	@DisplayName("012-01: options da bases, temas, máximo de 5 preguntas, cupo y voz, sin llamar a la IA ni anotar consumo")
	void t012_01_options() {
		RegisteredStudent student = studentWithProfile();
		int usageBefore = count("SELECT count(*) FROM ai_usage_event WHERE user_id = ?", student.id());

		JsonNode options = ok(student.api().get(PRACTICE + "/options"));

		assertThat(options.get("maxQuestions").asInt()).isEqualTo(5);
		assertThat(options.get("bases").get(0).get("basis").asString()).isEqualTo("PROFILE");
		assertThat(options.get("bases").get(0).get("available").asBoolean()).isTrue();
		assertThat(options.get("bases").get(1).get("basis").asString()).isEqualTo("VACANCY");
		assertThat(options.get("bases").get(1).get("vacancies").size()).isGreaterThan(0);
		List<String> topics = new ArrayList<>();
		options.get("topics").forEach(topic -> topics.add(topic.get("slug").asString()));
		assertThat(topics).containsExactlyInAnyOrder("java", "sql");
		assertThat(options.get("quota").get("used").asInt()).isZero();
		assertThat(options.get("quota").get("limit").asInt()).isEqualTo(20);
		assertThat(options.get("quota").get("resetsAt").asString()).isNotBlank();
		assertThat(options.get("voiceAvailable").asBoolean()).isTrue();
		assertThat(options.get("interviewNotice").get("version").asString()).isEqualTo("v2");
		assertThat(llm.journal()).isEmpty();
		assertThat(count("SELECT count(*) FROM ai_usage_event WHERE user_id = ?", student.id())).isEqualTo(usageBefore);

		JsonNode empty = ok(student().get(PRACTICE + "/options"));
		assertThat(empty.get("bases").get(0).get("available").asBoolean()).as("perfil sin datos").isFalse();
	}

	@Test
	@DisplayName("012-02: crear da 201 CREATED con las preguntas planificadas en UNA llamada a la IA")
	void t012_02_create() {
		RegisteredStudent student = studentWithProfile();

		JsonNode session = createPractice(student.api(), 4);

		assertThat(session.get("status").asString()).isEqualTo("CREATED");
		assertThat(session.get("mode").asString()).isEqualTo("PRACTICE");
		assertThat(session.get("questionCount").asInt()).isEqualTo(4);
		assertThat(session.get("questions").size()).as("las preguntas no se muestran antes de empezar").isZero();
		assertThat(llm.journal().stream().filter(r -> r.startsWith("PLAN")).count()).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM practice_question WHERE session_id = ?::uuid", id(session))).isEqualTo(4);
		assertThat(session.get("versions").get("promptVersion").asString()).isNotBlank();
		assertThat(session.get("versions").get("bankVersion").asString()).isEqualTo("bank-v1");
	}

	@Test
	@DisplayName("012-02: tema fuera de la lista, más de 5 preguntas o perfil sin datos dan 422; una sesión abierta da 409")
	void t012_02_validation() {
		RegisteredStudent student = studentWithProfile();
		ApiClient api = student.api();

		assertThat(errors(api.post(PRACTICE + "/sessions", body("basis", "PROFILE", "questionCount", 3, "topic", "php"))))
				.containsExactly("topic:not-allowed");
		assertThat(errors(api.post(PRACTICE + "/sessions", body("basis", "PROFILE", "questionCount", 6))))
				.containsExactly("questionCount:out-of-range");
		assertThat(errors(api.post(PRACTICE + "/sessions", body("basis", "OTRA", "questionCount", 2))))
				.containsExactly("basis:invalid");
		assertThat(errors(student().post(PRACTICE + "/sessions", body("basis", "PROFILE", "questionCount", 2))))
				.containsExactly("basis:no-confirmed-data");
		createPractice(api, 2);
		Response again = api.post(PRACTICE + "/sessions", body("basis", "PROFILE", "questionCount", 2));
		assertThat(again.status()).isEqualTo(409);
		assertThat(again.json().get("code").asString()).isEqualTo("session-open");
	}

	@Test
	@DisplayName("012-02: con base VACANCY y un tema de sus requisitos, las preguntas citan ese requisito")
	void t012_02_vacancyBasisWithTopic() {
		RegisteredStudent student = studentWithProfile();

		JsonNode session = ok(student.api().post(PRACTICE + "/sessions",
				body("basis", "VACANCY", "vacancyId", BACKEND_JAVA, "topic", "java", "questionCount", 2)));

		assertThat(jdbc.queryForList("SELECT basis_ref FROM practice_question WHERE session_id = ?::uuid", String.class,
				id(session))).containsOnly("requirement:java");
		assertThat(student.api().post(PRACTICE + "/sessions", body("basis", "VACANCY", "vacancyId",
				UUID.randomUUID().toString(), "questionCount", 2)).status()).isEqualTo(404);
	}

	@Test
	@DisplayName("012-03: a la IA solo viajan habilidades y proyectos; nunca nombre, apellidos, correo ni teléfono")
	void t012_03_planReceivesOnlyTheBasis() {
		String mark = letters("Zq");
		RegisteredStudent student = studentWithProfile(mark + " Segundo", "Apellido" + mark);

		createPractice(student.api(), 3);

		String plan = llm.journal().stream().filter(r -> r.startsWith("PLAN")).findFirst().orElseThrow();
		assertThat(plan).contains("skill:java = Java", "skill:sql = SQL", "project:1 = Gestor de tareas")
				.doesNotContain(mark).doesNotContain(student.email()).doesNotContain("912345678");
	}

	@Test
	@DisplayName("012-03: una pregunta sin basisRef válido o con un tema prohibido se reemplaza por una del banco")
	void t012_03_hostilePlanIsReplaced() {
		RegisteredStudent student = studentWithProfile();
		llm.planMode(Simulation.Mode.HOSTILE);

		JsonNode session = createPractice(student.api(), 3);

		List<String> texts = jdbc.queryForList("SELECT text FROM practice_question WHERE session_id = ?::uuid "
				+ "ORDER BY position", String.class, id(session));
		assertThat(texts).hasSize(3).noneMatch(text -> text.contains("estado civil") || text.contains("religión")
				|| text.length() > 500 || text.equals("corta"));
		assertThat(jdbc.queryForList("SELECT basis_ref FROM practice_question WHERE session_id = ?::uuid", String.class,
				id(session))).allMatch(ref -> ref.startsWith("skill:") || ref.startsWith("project:") || ref.equals("general"));
	}

	@Test
	@DisplayName("012-04: si la IA se cuelga, en question-timeout las preguntas salen del banco y la sesión queda degradada, sin error")
	void t012_04_hangingAiUsesTheBank() {
		RegisteredStudent student = studentWithProfile();
		llm.planMode(Simulation.Mode.HANG);

		long started = System.nanoTime();
		JsonNode session = createPractice(student.api(), 3);
		long elapsed = (System.nanoTime() - started) / 1_000_000;

		assertThat(elapsed).isBetween(7_500L, 10_000L);
		assertThat(session.get("degraded").toString()).contains("QUESTION_BANK");
		assertThat(jdbc.queryForList("SELECT source FROM practice_question WHERE session_id = ?::uuid", String.class,
				id(session))).containsOnly("BANK");
	}

	@Test
	@DisplayName("012-04: si la IA falla, las preguntas salen del banco con source BANK")
	void t012_04_failingAiUsesTheBank() {
		RegisteredStudent student = studentWithProfile();
		llm.planMode(Simulation.Mode.FAIL);

		JsonNode session = createPractice(student.api(), 2);

		assertThat(session.get("degraded").toString()).contains("QUESTION_BANK");
		JsonNode started = start(student.api(), id(session)).first("question").data();
		assertThat(started.get("generatedBy").asString()).isEqualTo("BANK");
	}

	@Test
	@DisplayName("012-21: historial paginado y detalle de SUS sesiones; borrar da 204 y se lleva todo; otra persona ve 404")
	void t012_21_historyAndDelete() {
		RegisteredStudent student = studentWithProfile();
		ApiClient api = student.api();
		String session = id(createPractice(api, 2));
		start(api, session);
		String turn = turnId(textTurn(api, session, 1, "Respuesta inventada del historial."));
		awaitFeedbackDone(turn);
		ok(api.post(DORA + session + "/finish", null));

		JsonNode page = ok(api.get(PRACTICE + "/sessions?page=1&size=5"));
		JsonNode detail = ok(api.get(PRACTICE + "/sessions/" + session));
		RegisteredStudent intruder = studentWithProfile();

		assertThat(page.get("total").asInt()).isEqualTo(1);
		assertThat(page.get("items").get(0).get("answered").asInt()).isEqualTo(1);
		assertThat(detail.get("questions").get(0).get("answer").get("transcriptFinal").asString())
				.isEqualTo("Respuesta inventada del historial.");
		assertThat(detail.get("questions").get(0).get("answer").get("feedback").get("generatedBy").asString())
				.isEqualTo("AI");
		assertThat(intruder.api().get(PRACTICE + "/sessions/" + session).status()).isEqualTo(404);
		assertThat(intruder.api().delete(PRACTICE + "/sessions/" + session).status()).isEqualTo(404);
		assertThat(api.delete(PRACTICE + "/sessions/" + session).status()).isEqualTo(204);
		assertThat(count("SELECT count(*) FROM practice_session WHERE id = ?::uuid", session)).isZero();
		assertThat(count("SELECT count(*) FROM practice_turn WHERE id = ?::uuid", turn)).isZero();
		assertThat(count("SELECT count(*) FROM practice_feedback WHERE turn_id = ?::uuid", turn)).isZero();
	}

	@Test
	@DisplayName("012-29: con el cupo del mes agotado, crear da 429 ai-quota-reached con resetsAt; borrar sigue permitido")
	void t012_29_quotaReached() {
		RegisteredStudent student = studentWithProfile();
		String kept = id(createPractice(student.api(), 1));
		ok(student.api().post(DORA + kept + "/finish", null));
		OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		for (int i = 0; i < 20; i++) {
			jdbc.update("""
					INSERT INTO ai_usage_event (id, occurred_at, user_id, function, level, adapter, outcome, read_id)
					VALUES (?, ?, ?, 'PRACTICE_SESSION', 'BASIC', 'simulated', 'SUCCESS', ?)""", UUID.randomUUID(), now,
					student.id(), UUID.randomUUID());
		}

		Response blocked = student.api().post(PRACTICE + "/sessions", body("basis", "PROFILE", "questionCount", 2));

		assertThat(blocked.status()).isEqualTo(429);
		assertThat(blocked.json().get("code").asString()).isEqualTo("ai-quota-reached");
		assertThat(blocked.json().get("title").asString()).contains("prácticas con DORA");
		assertThat(blocked.json().get("resetsAt").asString()).isNotBlank();
		assertThat(student.api().delete(PRACTICE + "/sessions/" + kept).status()).isEqualTo(204);
	}

	@Test
	@DisplayName("012-29: la sesión se anota como PRACTICE_SESSION SUCCESS al responder la primera pregunta, una sola vez, con su id")
	void t012_29_sessionCountedAtFirstAnswer() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 3));
		start(student.api(), session);
		assertThat(count("SELECT count(*) FROM ai_usage_event WHERE read_id = ?::uuid", session)).isZero();

		textTurn(student.api(), session, 1, "Primera respuesta inventada.");
		textTurn(student.api(), session, 2, "Segunda respuesta inventada.");

		assertThat(count("SELECT count(*) FROM ai_usage_event WHERE read_id = ?::uuid AND function = 'PRACTICE_SESSION' "
				+ "AND outcome = 'SUCCESS' AND user_id = ?", session, student.id())).isEqualTo(1);
		assertThat(ok(student.api().get(PRACTICE + "/options")).get("quota").get("used").asInt()).isEqualTo(1);
	}

	@Test
	@DisplayName("012-04: el banco base cubre la planificación aunque la IA se cuelgue después de varios segundos")
	void t012_04_slowAiWithinTimeoutIsUsed() {
		RegisteredStudent student = studentWithProfile();
		llm.planDelay(Duration.ofMillis(500));

		JsonNode session = createPractice(student.api(), 2);

		assertThat(session.get("degraded").size()).isZero();
		assertThat(jdbc.queryForList("SELECT source FROM practice_question WHERE session_id = ?::uuid", String.class,
				id(session))).containsOnly("AI");
	}

}
