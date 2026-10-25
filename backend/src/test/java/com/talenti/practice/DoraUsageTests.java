package com.talenti.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.talenti.practice.service.UsageMeter;
import com.talenti.support.ApiClient;
import com.talenti.support.SseClient;

import tools.jackson.databind.JsonNode;

/** Consumo, costo estimado y tope mensual de gasto (012-30). */
class DoraUsageTests extends PracticeIntegrationTest {

	@Autowired
	private UsageMeter usage;

	@Test
	@DisplayName("012-30: cada llamada a LLM, STT y TTS deja una fila con tamaños y costo estimado, sin contenido")
	void t012_30_everyCallIsMeasured() {
		RegisteredStudent student = studentWithProfile();
		ApiClient api = student.api();
		String session = id(createPractice(api, 2));
		start(api, session);
		String turn = turnId(audioTurn(api, session, 1, spoken("Respuesta inventada por voz para medir el consumo.")));
		awaitFeedbackDone(turn);

		await("filas de consumo", () -> count("SELECT count(DISTINCT function) FROM ai_usage_event WHERE user_id = ? "
				+ "AND function IN ('DORA_DIALOG', 'STT', 'TTS', 'PRACTICE_FEEDBACK')", student.id()) == 4);
		List<Map<String, Object>> rows = jdbc.queryForList("""
				SELECT function, outcome, input_size, output_size, estimated_cost_micros, model, read_id::text AS read_id
				FROM ai_usage_event WHERE user_id = ? AND function <> 'PRACTICE_SESSION'""", student.id());
		assertThat(rows).isNotEmpty().allSatisfy(row -> {
			assertThat(row.get("outcome")).isEqualTo("SUCCESS");
			assertThat((Long) row.get("estimated_cost_micros")).isNotNull().isGreaterThanOrEqualTo(0L);
			assertThat(row.get("read_id")).isNotEqualTo(session);
		});
		Map<String, Object> stt = rows.stream().filter(r -> r.get("function").equals("STT")).findFirst().orElseThrow();
		assertThat((Integer) stt.get("input_size")).as("milisegundos de audio").isBetween(1900, 2100);
		assertThat(rows.stream().filter(r -> r.get("function").equals("TTS")).mapToLong(
				r -> (Long) r.get("estimated_cost_micros")).sum()).as("la voz cuesta algo").isPositive();
		assertThat(count("SELECT count(DISTINCT read_id) FROM ai_usage_event WHERE user_id = ?", student.id()))
				.as("una fila por llamada").isEqualTo(count("SELECT count(*) FROM ai_usage_event WHERE user_id = ?",
						student.id()));
		assertThat(jdbc.queryForList("SELECT table_name || '.' || column_name FROM information_schema.columns "
				+ "WHERE table_name = 'ai_usage_event' AND data_type IN ('text', 'jsonb', 'bytea')", String.class))
				.as("la tabla no tiene dónde guardar contenido").isEmpty();
	}

	@Test
	@DisplayName("012-30: con el tope mensual de gasto alcanzado todo se degrada: banco base, plantilla, texto y retroalimentación UNAVAILABLE")
	void t012_30_spendCapDegradesEverything() {
		RegisteredStudent student = studentWithProfile();
		ApiClient api = student.api();
		UUID costly = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO ai_usage_event (id, occurred_at, function, level, adapter, outcome, read_id, estimated_cost_micros)
				VALUES (?, ?, 'DORA_DIALOG', 'ENHANCED', 'prueba', 'SUCCESS', ?, 999000000)""", UUID.randomUUID(),
				OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), costly);
		usage.refreshSpend();
		try {
			JsonNode options = ok(api.get(PRACTICE + "/options"));
			JsonNode session = createPractice(api, 2);
			SseClient.Result started = start(api, id(session));
			SseClient.Result text = textTurn(api, id(session), 1, "Respuesta inventada sin IA disponible.");
			SseClient.Result voice = audioTurn(api, id(session), 2, spoken("No hay voz a texto."));
			awaitFeedbackDone(turnId(text));

			assertThat(options.get("voiceAvailable").asBoolean()).isFalse();
			assertThat(session.get("degraded").toString()).contains("QUESTION_BANK");
			assertThat(started.has("reply.audio")).isFalse();
			assertThat(text.first("degraded").data().get("reason").asString()).isEqualTo("AI_LIMIT");
			assertThat(text.first("reply.text").data().get("source").asString()).isEqualTo("TEMPLATE");
			assertThat(text.has("reply.audio")).isFalse();
			assertThat(text.first("question").data().get("audio").isNull()).isTrue();
			assertThat(voice.first("error").data().get("code").asString()).isEqualTo("stt-failed");
			assertThat(feedbackStatus(turnId(text))).isEqualTo("UNAVAILABLE");
			assertThat(llm.journal()).as("ninguna llamada a la IA").isEmpty();
			assertThat(tts.journal()).isEmpty();
			assertThat(api.get(DORA + id(session) + "/questions/1/audio").status()).isEqualTo(503);
		}
		finally {
			jdbc.update("DELETE FROM ai_usage_event WHERE read_id = ?", costly);
			usage.refreshSpend();
		}
	}

}
