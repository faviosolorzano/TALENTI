package com.talenti.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.identity.Role;
import com.talenti.practice.adapter.Wav;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;
import com.talenti.support.SseClient;

/** Autorización, CSRF, 404 indistinto, no-store, límite de turnos y validación del audio (012-07, 012-32). */
class DoraSecurityTests extends PracticeIntegrationTest {

	@Test
	@DisplayName("012-32: sin sesión 401, sin CSRF 403 y con otro rol 403 en todas las rutas de DORA")
	void t012_32_authenticationCsrfAndRole() {
		String someId = UUID.randomUUID().toString();
		ApiClient anonymous = client();
		assertThat(anonymous.get(PRACTICE + "/options").status()).isEqualTo(401);
		assertThat(anonymous.get(PRACTICE + "/sessions").status()).isEqualTo(401);
		assertThat(sse(anonymous).postJson(DORA + someId + "/start", null).status()).isIn(401, 403);
		assertThat(anonymous.get("/api/v1/admin/dora/latency").status()).isEqualTo(401);

		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		assertThat(sse(student.api()).postJsonWithoutCsrf(DORA + session + "/start", Map.of()).status()).isEqualTo(403);
		assertThat(student.api().postWithoutCsrf(PRACTICE + "/sessions", body("basis", "PROFILE", "questionCount", 1))
				.status()).isEqualTo(403);
		assertThat(student.api().writeWithToken("DELETE", PRACTICE + "/sessions/" + session, null, null).status())
				.isEqualTo(403);
		assertThat(student.api().get("/api/v1/admin/dora/latency").status()).as("un estudiante no ve la latencia")
				.isEqualTo(403);

		for (Role role : List.of(Role.COMPANY, Role.ADMIN)) {
			CreatedUser user = createUser(role);
			ApiClient api = client();
			api.post("/api/v1/auth/login", loginBody(user.email(), user.password()));
			assertThat(api.get(PRACTICE + "/options").status()).as(role.name()).isEqualTo(403);
			assertThat(api.get(PRACTICE + "/sessions/" + session).status()).as(role.name()).isEqualTo(403);
			assertThat(sse(api).postJson(DORA + session + "/start", null).status()).as(role.name()).isEqualTo(403);
		}
	}

	@Test
	@DisplayName("012-32: una sesión ajena o inexistente da 404 session-not-found sin distinguir, y las respuestas son no-store")
	void t012_32_notFoundAndNoStore() {
		RegisteredStudent owner = studentWithProfile();
		String session = id(createPractice(owner.api(), 2));
		RegisteredStudent intruder = studentWithProfile();

		Response foreign = intruder.api().get(PRACTICE + "/sessions/" + session);
		Response missing = intruder.api().get(PRACTICE + "/sessions/" + UUID.randomUUID());
		Response malformed = intruder.api().get(PRACTICE + "/sessions/no-es-un-id");
		SseClient.Result foreignStart = sse(intruder.api()).postJson(DORA + session + "/start", null);
		SseClient.Result foreignTurn = textTurn(intruder.api(), session, 1, "Intento ajeno.");
		Response foreignFinish = intruder.api().post(DORA + session + "/finish", null);
		Response foreignAudio = intruder.api().get(DORA + session + "/questions/1/audio");

		for (Response response : List.of(foreign, missing, malformed, foreignFinish, foreignAudio)) {
			assertThat(response.status()).isEqualTo(404);
			assertThat(response.json().get("code").asString()).isEqualTo("session-not-found");
		}
		assertThat(foreign.body()).isEqualTo(missing.body().replace(missing.json().get("instance").asString(),
				foreign.json().get("instance").asString()));
		assertThat(foreignStart.status()).isEqualTo(404);
		assertThat(foreignTurn.status()).isEqualTo(404);
		assertThat(foreignStart.problem().get("code").asString()).isEqualTo("session-not-found");
		assertThat(sessionStatus(session)).isEqualTo("CREATED");

		assertThat(owner.api().get(PRACTICE + "/options").header("Cache-Control")).contains("no-store");
		assertThat(owner.api().get(PRACTICE + "/sessions/" + session).header("Cache-Control")).contains("no-store");
		SseClient.Result started = start(owner.api(), session);
		assertThat(started.cacheControl()).contains("no-store");
		assertThat(textTurn(owner.api(), session, 1, "Respuesta inventada.").cacheControl()).contains("no-store");
	}

	@Test
	@DisplayName("012-32: más de 30 turnos por minuto por persona dan 429")
	void t012_32_turnRateLimit() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		start(student.api(), session);

		int conflicts = 0;
		for (int i = 0; i < 30; i++) {
			if (textTurn(student.api(), session, 5, "Fuera de orden, solo para contar.").status() == 409) {
				conflicts++;
			}
		}
		SseClient.Result limited = textTurn(student.api(), session, 1, "Este ya no pasa.");

		assertThat(conflicts).isEqualTo(30);
		assertThat(limited.status()).isEqualTo(429);
		assertThat(limited.problem().get("code").asString()).isEqualTo("too-many-requests");
	}

	@Test
	@DisplayName("012-07: el audio se valida por su contenido: un texto disfrazado de audio/webm da 415 antes de abrir el flujo")
	void t012_07_audioIsValidatedByContent() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		start(student.api(), session);

		SseClient.Result disguised = sse(student.api()).postAudio(DORA + session + "/turns",
				"esto no es audio, es texto con nombre de audio".getBytes(java.nio.charset.StandardCharsets.UTF_8),
				"audio/webm", Map.of("questionPosition", "1", "clientTurnId", UUID.randomUUID().toString()));
		SseClient.Result wavDeclaredAsMp4 = sse(student.api()).postAudio(DORA + session + "/turns",
				spoken("Un WAV real aunque diga otra cosa."), "audio/mp4",
				Map.of("questionPosition", "1", "clientTurnId", UUID.randomUUID().toString()));

		assertThat(disguised.status()).isEqualTo(415);
		assertThat(disguised.contentType()).doesNotStartWith("text/event-stream");
		assertThat(disguised.problem().get("code").asString()).isEqualTo("audio-unsupported");
		assertThat(wavDeclaredAsMp4.status()).as("se reconoce por el contenido").isEqualTo(200);
	}

	@Test
	@DisplayName("012-07: más de 3 MB o más de 120 s de audio dan 413 antes de abrir el flujo")
	void t012_07_audioLimits() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		start(student.api(), session);

		byte[] huge = new byte[3 * 1024 * 1024 + 200 * 1024];
		System.arraycopy(Wav.of(10, false, null, null), 0, huge, 0, 44);
		SseClient.Result tooLarge = sse(student.api()).postAudio(DORA + session + "/turns", huge, "audio/wav",
				Map.of("questionPosition", "1", "clientTurnId", UUID.randomUUID().toString()));
		SseClient.Result tooLong = sse(student.api()).postAudio(DORA + session + "/turns",
				Wav.of(121_000, false, null, null), "audio/wav",
				Map.of("questionPosition", "1", "clientTurnId", UUID.randomUUID().toString()));

		assertThat(tooLarge.status()).isEqualTo(413);
		assertThat(tooLarge.problem().get("code").asString()).isEqualTo("audio-too-large");
		assertThat(tooLong.status()).isEqualTo(413);
		assertThat(tooLong.problem().get("code").asString()).isEqualTo("audio-too-long");
		assertThat(stt.journal()).as("no se llamó a la voz a texto").isEmpty();
	}

}
