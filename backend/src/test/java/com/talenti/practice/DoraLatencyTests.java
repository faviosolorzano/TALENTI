package com.talenti.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.identity.Role;
import com.talenti.practice.adapter.Simulation;
import com.talenti.practice.domain.Percentiles;
import com.talenti.support.ApiClient;
import com.talenti.support.SseClient;

import tools.jackson.databind.JsonNode;

/**
 * Latencia y degradación con simulados de demoras fijas (012-11, 012-15 a 012-17). Las demoras son reales pero
 * controladas (sin red ni IA), y los márgenes, los de la spec: así la prueba es estable.
 */
class DoraLatencyTests extends PracticeIntegrationTest {

	private static final long STT_MS = 400;

	private static final long FIRST_CHUNK_MS = 500;

	private static final long LLM_TOTAL_MS = 3000;

	private static final long TTS_MS = 300;

	/** Margen del lado del cliente (envío del multipart y lectura del flujo); la meta de la spec se mide en el servidor. */
	private static final long CLIENT_TRANSPORT_MS = 250;

	@Test
	@DisplayName("012-16 y 012-15: en 50 turnos el primer reply.audio llega en ≤ STT + primer fragmento + TTS + 250 ms (p95), antes de que el LLM termine")
	void t012_16_firstAudioWithinBudgetAndBeforeTheLlmEnds() throws Exception {
		// 10 personas, 5 preguntas cada una: 50 turnos por voz, de a 5 personas a la vez (carga realista para la demo
		// sin que la máquina de pruebas, compartida, mida su propia saturación en vez de la del servidor).
		List<RegisteredStudent> students = new ArrayList<>();
		List<String> sessions = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			RegisteredStudent student = studentWithProfile();
			String session = id(createPractice(student.api(), 5));
			start(student.api(), session);
			students.add(student);
			sessions.add(session);
		}
		stt.delay(Duration.ofMillis(STT_MS));
		llm.bridgeDelays(Duration.ofMillis(FIRST_CHUNK_MS), Duration.ofMillis(LLM_TOTAL_MS));
		tts.delay(Duration.ofMillis(TTS_MS));

		List<Long> serverFirstAudio = Collections.synchronizedList(new ArrayList<>());
		List<Long> clientFirstAudio = Collections.synchronizedList(new ArrayList<>());
		List<Long> marginBeforeLlmEnd = Collections.synchronizedList(new ArrayList<>());
		ExecutorService pool = Executors.newFixedThreadPool(5);
		try {
			List<Future<?>> work = new ArrayList<>();
			for (int i = 0; i < 10; i++) {
				ApiClient api = students.get(i).api();
				String session = sessions.get(i);
				work.add(pool.submit(() -> {
					for (int position = 1; position <= 5; position++) {
						SseClient.Result result = audioTurn(api, session, position,
								spoken("Respuesta inventada número " + position + " con Java y SQL."));
						assertThat(result.names()).as("turno %s", position).contains("reply.audio", "turn.done");
						JsonNode timings = result.first("turn.done").data().get("timings");
						serverFirstAudio.add(timings.get("firstAudioMs").asLong());
						clientFirstAudio.add(result.millisTo(result.first("reply.audio")));
						marginBeforeLlmEnd.add(timings.get("llmDoneMs").asLong() - timings.get("firstAudioMs").asLong());
					}
					return null;
				}));
			}
			for (Future<?> future : work) {
				future.get();
			}
		}
		finally {
			pool.shutdownNow();
		}

		long budget = STT_MS + FIRST_CHUNK_MS + TTS_MS + 250;
		assertThat(serverFirstAudio).hasSize(50);
		assertThat(Percentiles.of(serverFirstAudio, 95)).as("p95 en el servidor").isLessThanOrEqualTo(budget);
		// El cliente suma el envío del audio y la red local: se le da un margen de transporte aparte.
		assertThat(Percentiles.of(clientFirstAudio, 95)).as("p95 medido por el cliente")
				.isLessThanOrEqualTo(budget + CLIENT_TRANSPORT_MS);
		assertThat(marginBeforeLlmEnd).as("el primer audio sale antes de que el LLM termine (streaming)")
				.allMatch(margin -> margin > 1000);

		CreatedUser admin = createUser(Role.ADMIN);
		ApiClient adminApi = client();
		adminApi.post("/api/v1/auth/login", loginBody(admin.email(), admin.password()));
		JsonNode latency = ok(adminApi.get("/api/v1/admin/dora/latency?last=50"));
		assertThat(latency.get("count").asInt()).isEqualTo(50);
		assertThat(latency.get("p95").asLong()).isLessThanOrEqualTo(budget);
		assertThat(latency.get("p50").asLong()).isLessThanOrEqualTo(latency.get("p95").asLong());
		assertThat(latency.toString()).doesNotContain("Respuesta inventada");
	}

	@Test
	@DisplayName("012-17: LLM colgado: frase de plantilla en ≤ bridge-timeout + 250 ms, y el turno termina")
	void t012_17_hangingLlmUsesTheTemplate() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		start(student.api(), session);
		llm.bridgeMode(Simulation.Mode.HANG);

		SseClient.Result result = textTurn(student.api(), session, 1, "Respuesta inventada mientras la IA no responde.");

		SseClient.Event reply = result.first("reply.text");
		assertThat(reply.data().get("source").asString()).isEqualTo("TEMPLATE");
		assertThat(result.millisTo(reply)).isLessThanOrEqualTo(1500 + 250);
		assertThat(result.names()).contains("question").last().isEqualTo("turn.done");
		assertThat(jdbc.queryForObject("SELECT bridge_source FROM practice_turn WHERE id = ?::uuid", String.class,
				turnId(result))).isEqualTo("TEMPLATE");
	}

	@Test
	@DisplayName("012-17 y 012-11: STT colgado: error stt-failed reintentable en ≤ stt-timeout + 250 ms; la persona puede escribir")
	void t012_17_hangingSttFails() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		start(student.api(), session);
		stt.mode(Simulation.Mode.HANG);

		SseClient.Result result = audioTurn(student.api(), session, 1, spoken("Nadie escuchará esto."));

		SseClient.Event error = result.first("error");
		assertThat(result.names()).containsExactly("error");
		assertThat(error.data().get("code").asString()).isEqualTo("stt-failed");
		assertThat(error.data().get("retryable").asBoolean()).isTrue();
		assertThat(result.millisTo(error)).isLessThanOrEqualTo(6000 + 250);
		assertThat(textTurn(student.api(), session, 1, "Lo escribo entonces.").status()).isEqualTo(200);
	}

	@Test
	@DisplayName("012-17 y 012-11: TTS colgado: el turno sigue con texto y degraded TTS_UNAVAILABLE en ≤ tts-timeout + 250 ms")
	void t012_17_hangingTtsDegradesToText() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 3));
		start(student.api(), session);
		tts.mode(Simulation.Mode.HANG);

		SseClient.Result result = textTurn(student.api(), session, 1, "Respuesta inventada sin voz de DORA.");

		SseClient.Event degraded = result.first("degraded");
		assertThat(degraded.data().get("reason").asString()).isEqualTo("TTS_UNAVAILABLE");
		assertThat(result.millisTo(degraded)).isLessThanOrEqualTo(4000 + 250);
		assertThat(result.all("degraded")).hasSize(1);
		assertThat(result.has("reply.text")).isTrue();
		assertThat(result.first("question").data().get("text").asString()).isNotBlank();
		assertThat(result.names()).last().isEqualTo("turn.done");
		assertThat(sessionStatus(session)).isEqualTo("IN_PROGRESS");
	}

	@Test
	@DisplayName("012-11: si la voz falla, el turno sigue con texto; si la transcripción falla una vez y queda tiempo, se reintenta")
	void t012_11_failuresDegradeOrRetry() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 3));
		start(student.api(), session);

		tts.mode(Simulation.Mode.FAIL);
		SseClient.Result noVoice = textTurn(student.api(), session, 1, "Respuesta inventada.");
		assertThat(noVoice.first("degraded").data().get("reason").asString()).isEqualTo("TTS_UNAVAILABLE");
		assertThat(noVoice.has("reply.audio")).isFalse();
		assertThat(noVoice.names()).last().isEqualTo("turn.done");

		tts.mode(Simulation.Mode.NORMAL);
		stt.failNext(1);
		SseClient.Result retried = audioTurn(student.api(), session, 2, spoken("Se escuchó al segundo intento."));
		assertThat(retried.first("transcript").data().get("text").asString()).isEqualTo("Se escuchó al segundo intento.");
		assertThat(stt.journal()).hasSize(2);

		stt.mode(Simulation.Mode.FAIL);
		SseClient.Result failed = audioTurn(student.api(), session, 3, spoken("No se escuchará."));
		assertThat(failed.first("error").data().get("code").asString()).isEqualTo("stt-failed");
		assertThat(jdbc.queryForObject("SELECT degraded_reasons::text FROM practice_session WHERE id = ?::uuid",
				String.class, session)).contains("TTS_UNAVAILABLE").contains("STT_FAILED");
	}

}
