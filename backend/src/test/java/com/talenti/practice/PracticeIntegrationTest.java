package com.talenti.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;

import com.talenti.matching.MatchingIntegrationTest;
import com.talenti.practice.adapter.SimulatedLlm;
import com.talenti.practice.adapter.SimulatedSpeechToText;
import com.talenti.practice.adapter.SimulatedTextToSpeech;
import com.talenti.practice.adapter.Wav;
import com.talenti.practice.service.SessionLifecycle;
import com.talenti.support.ApiClient;
import com.talenti.support.SseClient;

import tools.jackson.databind.JsonNode;

/**
 * Base de las pruebas de integración de DORA (spec 012): aplicación completa con servidor HTTP real, PostgreSQL real,
 * los datos de ejemplo (vacantes) y los adaptadores SIMULADOS programables (ninguna prueba llama a la API real). Todos
 * los datos son inventados; los audios los generan las pruebas.
 */
public abstract class PracticeIntegrationTest extends MatchingIntegrationTest {

	protected static final String PRACTICE = "/api/v1/practice";

	protected static final String DORA = "/api/v1/dora/sessions/";

	@Autowired
	protected SimulatedLlm llm;

	@Autowired
	protected SimulatedSpeechToText stt;

	@Autowired
	protected SimulatedTextToSpeech tts;

	@Autowired
	protected SessionLifecycle lifecycle;

	@AfterEach
	void resetSimulators() {
		llm.reset();
		stt.reset();
		tts.reset();
		clock.set(Instant.now());
	}

	/** Una marca única solo con letras (los nombres del perfil no admiten números). */
	protected static String letters(String prefix) {
		StringBuilder mark = new StringBuilder(prefix);
		java.util.Random random = new java.util.Random();
		for (int i = 0; i < 10; i++) {
			mark.append((char) ('a' + random.nextInt(26)));
		}
		return mark.toString();
	}

	protected SseClient sse(ApiClient api) {
		return new SseClient(api, port);
	}

	/** Un estudiante con nombre (primer nombre {@code firstName}), habilidades y un proyecto confirmados. */
	protected RegisteredStudent studentWithProfile(String firstNames, String lastNames) {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		ok(api.put(PROFILE + "/skills", skills(skill("java", 2), skill("sql", 1))));
		ok(api.post(PROFILE + "/projects", project("Gestor de tareas", description(), "Java", "PostgreSQL")));
		if (firstNames != null) {
			ok(api.put(PROFILE + "/about", body("firstNames", firstNames, "lastNames", lastNames, "contactEmail",
					"contacto." + UUID.randomUUID().toString().substring(0, 8) + "@ejemplo.test", "phone", "912345678",
					"about", "Estudiante de prueba con datos inventados.")));
		}
		return student;
	}

	protected RegisteredStudent studentWithProfile() {
		return studentWithProfile("Lucía", "Prueba Inventada");
	}

	protected JsonNode createPractice(ApiClient api, int questions) {
		ApiClient.Response response = api.post(PRACTICE + "/sessions",
				body("basis", "PROFILE", "questionCount", questions, "voice", true));
		assertThat(response.status()).as("crear práctica: %s", response.body()).isEqualTo(201);
		return response.json();
	}

	protected static String id(JsonNode session) {
		return session.get("id").asString();
	}

	protected SseClient.Result start(ApiClient api, String sessionId) {
		SseClient.Result result = sse(api).postJson(DORA + sessionId + "/start", null);
		assertThat(result.status()).as("inicio: %s", result.body()).isEqualTo(200);
		return result;
	}

	protected SseClient.Result textTurn(ApiClient api, String sessionId, int position, String text) {
		return textTurn(api, sessionId, position, text, UUID.randomUUID());
	}

	protected SseClient.Result textTurn(ApiClient api, String sessionId, int position, String text, UUID clientTurnId) {
		return sse(api).postJson(DORA + sessionId + "/turns",
				body("text", text, "questionPosition", position, "clientTurnId", clientTurnId.toString()));
	}

	protected SseClient.Result audioTurn(ApiClient api, String sessionId, int position, byte[] audio) {
		return sse(api).postAudio(DORA + sessionId + "/turns", audio, "audio/webm",
				Map.of("questionPosition", String.valueOf(position), "clientTurnId", UUID.randomUUID().toString()));
	}

	/** Un WAV inventado de 2 segundos con lo que el reconocedor simulado "escuchará". */
	protected static byte[] spoken(String text) {
		return Wav.of(2000, false, text, null);
	}

	/** Espera (hasta 10 s) a que la condición se cumpla; para lo que corre en segundo plano. */
	protected static void await(String what, BooleanSupplier condition) {
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(25);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}
		throw new AssertionError("No se cumplió a tiempo: " + what);
	}

	protected String feedbackStatus(String turnId) {
		List<String> status = jdbc.queryForList("SELECT status FROM practice_feedback WHERE turn_id = ?::uuid",
				String.class, turnId);
		return status.isEmpty() ? null : status.get(0);
	}

	protected void awaitFeedbackDone(String turnId) {
		await("retroalimentación de " + turnId, () -> {
			String status = feedbackStatus(turnId);
			return status != null && !status.equals("PENDING");
		});
	}

	protected String sessionStatus(String sessionId) {
		return jdbc.queryForObject("SELECT status FROM practice_session WHERE id = ?::uuid", String.class, sessionId);
	}

	protected static String turnId(SseClient.Result result) {
		return result.first("transcript").data().get("turnId").asString();
	}

	/** Postula a la vacante "Practicante Backend Java" y devuelve el id de la postulación. */
	protected String apply(ApiClient api) {
		ApiClient.Response response = api.post("/api/v1/applications", Map.of("vacancyId", BACKEND_JAVA));
		assertThat(response.status()).as("postular: %s", response.body()).isEqualTo(201);
		return response.json().get("id").asString();
	}

	protected String applicationStatus(String applicationId) {
		return jdbc.queryForObject("SELECT status FROM application WHERE id = ?::uuid", String.class, applicationId);
	}

}
