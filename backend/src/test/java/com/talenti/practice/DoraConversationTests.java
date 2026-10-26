package com.talenti.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.practice.adapter.Wav;
import com.talenti.support.ApiClient;
import com.talenti.support.SseClient;

import tools.jackson.databind.JsonNode;

/** La conversación por turnos con DORA (012-05, 012-06, 012-08, 012-10, 012-12 a 012-14), con simulados. */
class DoraConversationTests extends PracticeIntegrationTest {

	@Test
	@DisplayName("012-05: start emite el saludo fijo con la identificación como IA, el aviso de privacidad y la pregunta 1 con audio")
	void t012_05_startGreetsAndAsksTheFirstQuestion() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 3));

		SseClient.Result result = start(student.api(), session);

		assertThat(result.contentType()).startsWith("text/event-stream");
		assertThat(result.names()).containsExactly("reply.text", "reply.audio", "question", "turn.done");
		JsonNode greeting = result.first("reply.text").data();
		assertThat(greeting.get("text").asString()).contains("Soy DORA, una asistente de inteligencia artificial")
				.contains("privada").contains("no se comparte con ninguna empresa");
		assertThat(greeting.get("source").asString()).isEqualTo("TEMPLATE");
		JsonNode question = result.first("question").data();
		assertThat(question.get("position").asInt()).isEqualTo(1);
		assertThat(question.get("generatedBy").asString()).isIn("AI", "BANK");
		assertThat(question.get("audio").get("data").asString()).isNotBlank();
		assertThat(result.first("turn.done").data().get("nextPosition").asInt()).isEqualTo(1);
		assertThat(sessionStatus(session)).isEqualTo("IN_PROGRESS");
	}

	@Test
	@DisplayName("012-06: un turno por texto emite transcript, frase puente en texto y audio, la siguiente pregunta y turn.done, en orden")
	void t012_06_textTurnEventsInOrder() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 3));
		start(student.api(), session);

		SseClient.Result result = textTurn(student.api(), session, 1, "Usé Java con Spring Boot para una API de tareas.");

		List<String> names = result.names();
		assertThat(names.get(0)).isEqualTo("transcript");
		assertThat(names).contains("reply.text", "reply.audio", "question");
		assertThat(names.get(names.size() - 1)).isEqualTo("turn.done");
		assertThat(names.indexOf("question")).isGreaterThan(names.lastIndexOf("reply.audio"));
		assertThat(names.indexOf("reply.text")).isLessThan(names.indexOf("reply.audio"));
		assertThat(result.first("transcript").data().get("inputMode").asString()).isEqualTo("TEXT");
		assertThat(result.first("reply.text").data().get("generatedBy").asString()).isEqualTo("AI");
		assertThat(result.first("question").data().get("position").asInt()).isEqualTo(2);
		assertThat(result.first("turn.done").data().get("nextPosition").asInt()).isEqualTo(2);
	}

	@Test
	@DisplayName("012-06: repetir el mismo clientTurnId devuelve lo guardado sin volver a llamar a la IA ni a la voz")
	void t012_06_sameClientTurnIdReplays() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 3));
		start(student.api(), session);
		UUID clientTurnId = UUID.randomUUID();
		SseClient.Result first = textTurn(student.api(), session, 1, "Primera respuesta inventada con Java.",
				clientTurnId);
		awaitFeedbackDone(turnId(first));
		int llmCalls = llm.journal().size();
		int ttsCalls = tts.journal().size();

		SseClient.Result again = textTurn(student.api(), session, 1, "Otro texto que no debe guardarse.", clientTurnId);

		assertThat(again.status()).isEqualTo(200);
		assertThat(again.first("transcript").data().get("turnId").asString()).isEqualTo(turnId(first));
		assertThat(again.first("transcript").data().get("text").asString()).isEqualTo("Primera respuesta inventada con Java.");
		assertThat(again.first("turn.done").data().get("replayed").asBoolean()).isTrue();
		assertThat(llm.journal()).hasSize(llmCalls);
		assertThat(tts.journal()).hasSize(ttsCalls);
		assertThat(count("SELECT count(*) FROM practice_turn WHERE client_turn_id = ?", clientTurnId)).isEqualTo(1);
	}

	@Test
	@DisplayName("012-06 y 012-12: un turno por audio se transcribe y se guarda con modo VOICE, duración y tiempos, sin audio")
	void t012_12_voiceTurnStoresTranscriptAndTimings() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		start(student.api(), session);

		SseClient.Result result = audioTurn(student.api(), session, 1, spoken("Respondo por voz que usé SQL."));

		assertThat(result.first("transcript").data().get("text").asString()).isEqualTo("Respondo por voz que usé SQL.");
		assertThat(result.first("transcript").data().get("inputMode").asString()).isEqualTo("VOICE");
		var row = jdbc.queryForMap("SELECT input_mode, transcript_raw, transcript_final, audio_ms, stt_ms, "
				+ "first_audio_ms, total_ms, bridge_source FROM practice_turn WHERE id = ?::uuid", turnId(result));
		assertThat(row.get("input_mode")).isEqualTo("VOICE");
		assertThat(row.get("transcript_raw")).isEqualTo("Respondo por voz que usé SQL.");
		assertThat(row.get("transcript_final")).isEqualTo(row.get("transcript_raw"));
		assertThat((Integer) row.get("audio_ms")).isBetween(1900, 2100);
		assertThat(row.get("stt_ms")).isNotNull();
		assertThat(row.get("first_audio_ms")).isNotNull();
		assertThat((Integer) row.get("total_ms")).isGreaterThanOrEqualTo((Integer) row.get("first_audio_ms"));
		assertThat(row.get("bridge_source")).isEqualTo("AI");
		assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name LIKE 'practice_%' "
				+ "AND data_type = 'bytea'")).as("ninguna columna guarda audio").isZero();
	}

	@Test
	@DisplayName("012-08: un audio en silencio da error no-speech, no avanza la pregunta ni cuenta como respuesta")
	void t012_08_silenceIsNoSpeech() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		start(student.api(), session);

		SseClient.Result result = audioTurn(student.api(), session, 1, Wav.of(1500, true, null, null));

		assertThat(result.names()).containsExactly("error");
		assertThat(result.first("error").data().get("code").asString()).isEqualTo("no-speech");
		assertThat(result.first("error").data().get("retryable").asBoolean()).isTrue();
		assertThat(count("SELECT count(*) FROM practice_turn t JOIN practice_question q ON q.id = t.question_id "
				+ "WHERE q.session_id = ?::uuid", session)).isZero();
		assertThat(textTurn(student.api(), session, 1, "Ahora sí respondo.").status()).isEqualTo(200);
	}

	@Test
	@DisplayName("012-10: el audio de la siguiente pregunta llega ya preparado y se puede volver a escuchar")
	void t012_10_nextQuestionAudioIsPrefetched() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 3));
		start(student.api(), session);
		tts.delay(Duration.ofMillis(300));

		SseClient.Result result = textTurn(student.api(), session, 1, "Respuesta inventada para la primera pregunta.");

		SseClient.Event lastReplyAudio = result.all("reply.audio").get(result.all("reply.audio").size() - 1);
		SseClient.Event question = result.first("question");
		assertThat(question.data().get("audio").get("data").asString()).isNotBlank();
		assertThat((question.atNanos() - lastReplyAudio.atNanos()) / 1_000_000)
				.as("la pregunta no espera una síntesis nueva de 300 ms").isLessThan(200);
		ApiClient.Response audio = student.api().get(DORA + session + "/questions/2/audio");
		assertThat(audio.status()).isEqualTo(200);
		assertThat(audio.header("Content-Type")).startsWith("audio/");
		assertThat(student.api().get(DORA + session + "/questions/3/audio").status())
				.as("una pregunta aún no hecha no se puede escuchar").isEqualTo(404);
	}

	@Test
	@DisplayName("012-13: corregir la transcripción guarda la final, conserva la original y recalcula la retroalimentación")
	void t012_13_correctTranscript() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		start(student.api(), session);
		String turn = turnId(textTurn(student.api(), session, 1, "Texto original inventado."));
		awaitFeedbackDone(turn);

		ApiClient.Response patched = student.api().writeWithToken("PATCH", DORA + session + "/turns/" + turn,
				body("transcript", "Texto corregido inventado con un ejemplo de Java y SQL en un proyecto."),
				student.api().cookie("XSRF-TOKEN"));

		assertThat(patched.status()).as(patched.body()).isEqualTo(200);
		assertThat(patched.json().get("transcriptRaw").asString()).isEqualTo("Texto original inventado.");
		assertThat(patched.json().get("transcriptFinal").asString()).startsWith("Texto corregido");
		awaitFeedbackDone(turn);
		assertThat(llm.journal().stream().filter(r -> r.startsWith("EVALUATE")).toList()).hasSize(2)
				.last().asString().contains("Texto corregido");
		assertThat(student.api().writeWithToken("PATCH", DORA + session + "/turns/" + turn,
				body("transcript", "x".repeat(4001)), student.api().cookie("XSRF-TOKEN")).status()).isEqualTo(422);
	}

	@Test
	@DisplayName("012-13: en la práctica se puede repetir la última respuesta una vez; la segunda vez da 409")
	void t012_13_repeatLastAnswerOnce() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 3));
		start(student.api(), session);
		textTurn(student.api(), session, 1, "Primera versión inventada.");

		SseClient.Result repeated = textTurn(student.api(), session, 1, "Segunda versión inventada.");
		SseClient.Result third = textTurn(student.api(), session, 1, "Tercera versión inventada.");
		SseClient.Result skip = textTurn(student.api(), session, 3, "Me salto una pregunta.");

		assertThat(repeated.status()).isEqualTo(200);
		assertThat(third.status()).isEqualTo(409);
		assertThat(third.problem().get("code").asString()).isEqualTo("retry-limit-reached");
		assertThat(skip.status()).isEqualTo(409);
		assertThat(skip.problem().get("code").asString()).isEqualTo("question-out-of-order");
		JsonNode detail = ok(student.api().get(PRACTICE + "/sessions/" + session));
		assertThat(detail.get("questions").get(0).get("answer").get("transcriptFinal").asString())
				.isEqualTo("Segunda versión inventada.");
		assertThat(detail.get("questions").get(0).get("answer").get("attempt").asInt()).isEqualTo(2);
	}

	@Test
	@DisplayName("012-14: finish la deja COMPLETED; la inactividad, ABANDONED; un reinicio, FAILED")
	void t012_14_closingStates() {
		RegisteredStudent first = studentWithProfile();
		String finished = id(createPractice(first.api(), 2));
		start(first.api(), finished);
		JsonNode closed = ok(first.api().post(DORA + finished + "/finish", null));
		assertThat(closed.get("status").asString()).isEqualTo("COMPLETED");
		assertThat(sse(first.api()).postJson(DORA + finished + "/start", null).status()).isEqualTo(409);

		RegisteredStudent second = studentWithProfile();
		String idle = id(createPractice(second.api(), 2));
		start(second.api(), idle);
		clock.advance(Duration.ofMinutes(16));
		lifecycle.sweepInactive();
		assertThat(sessionStatus(idle)).isEqualTo("ABANDONED");

		clock.set(java.time.Instant.now());
		RegisteredStudent third = studentWithProfile();
		String open = id(createPractice(third.api(), 2));
		lifecycle.recoverAfterRestart();
		assertThat(sessionStatus(open)).isEqualTo("FAILED");
		// La falla de plataforma se anota como compensación de esa sesión (012-29, corrección DORA-01).
		assertThat(count("SELECT count(*) FROM ai_usage_event WHERE reverses_read_id = ?::uuid AND outcome = 'FAILED_PLATFORM'",
				open)).isEqualTo(1);
	}

	@Test
	@DisplayName("012-14: dos turnos a la vez en la misma sesión: uno recibe 409 turn-in-progress")
	void t012_14_concurrentTurnsConflict() throws Exception {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 3));
		start(student.api(), session);
		llm.bridgeDelays(Duration.ofMillis(100), Duration.ofMillis(1500));

		var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
		try {
			var a = executor.submit(() -> textTurn(student.api(), session, 1, "Respuesta A inventada."));
			Thread.sleep(300);
			var b = executor.submit(() -> textTurn(student.api(), session, 1, "Respuesta B inventada."));
			List<Integer> statuses = List.of(a.get().status(), b.get().status());
			assertThat(statuses).containsExactly(200, 409);
			assertThat(b.get().problem().get("code").asString()).isEqualTo("turn-in-progress");
		}
		finally {
			executor.shutdownNow();
		}
	}

}
