package com.talenti.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.talenti.practice.adapter.Wav;
import com.talenti.practice.port.ConversationPort;
import com.talenti.practice.port.LlmPort;
import com.talenti.practice.port.SpokenAudio;
import com.talenti.practice.service.DoraService;
import com.talenti.practice.service.Views;
import com.talenti.support.ApiClient;
import com.talenti.support.SseClient;

/**
 * 012-33 y 012-07: con el registro al máximo, ni los registros, ni la auditoría, ni los errores, ni {@code toString}
 * contienen audio, transcripciones, preguntas, retroalimentación ni el nombre; y el audio no se guarda en ninguna
 * tabla. Se comprueba con marcas únicas.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"talenti.demo-data.enabled=true", "talenti.test.context=dora-never-logged", "logging.level.com.talenti=TRACE",
		"logging.level.org.springframework.security=TRACE", "logging.level.org.springframework.web=TRACE",
		"logging.level.org.springframework.jdbc=DEBUG", "logging.level.org.springframework.transaction=TRACE",
		"spring.mvc.log-request-details=true" })
class DoraNeverLoggedTests extends PracticeIntegrationTest {

	@Test
	@DisplayName("012-33 y 012-07: un recorrido completo no deja en registros, auditoría ni errores el nombre, las respuestas, las preguntas ni el audio")
	void t012_33_noContentInLogs(CapturedOutput output) {
		String name = letters("Nm");
		String answer = "respuesta" + UUID.randomUUID().toString().replace("-", "");
		String spokenMark = "dicho" + UUID.randomUUID().toString().replace("-", "");
		String audioMark = "audio" + UUID.randomUUID().toString().replace("-", "");
		RegisteredStudent student = studentWithProfile(name, "Apellido Inventado");
		ApiClient api = student.api();

		String session = id(createPractice(api, 3));
		SseClient.Result started = start(api, session);
		String question = started.first("question").data().get("text").asString();
		SseClient.Result text = textTurn(api, session, 1, "Mi " + answer + " sobre Java.");
		SseClient.Result voice = sse(api).postAudio(DORA + session + "/turns",
				Wav.of(1500, false, "Lo que dije " + spokenMark, audioMark), "audio/webm",
				java.util.Map.of("questionPosition", "2", "clientTurnId", UUID.randomUUID().toString()));
		awaitFeedbackDone(turnId(text));
		String turn = turnId(text);
		String feedback = ok(api.get(PRACTICE + "/sessions/" + session)).get("questions").get(0).get("answer")
				.get("feedback").toString();
		ApiClient.Response tooLong = api.writeWithToken("PATCH", DORA + session + "/turns/" + turn,
				body("transcript", answer + "x".repeat(4000)), api.cookie("XSRF-TOKEN"));
		ok(api.post(DORA + session + "/finish", null));
		ApiClient.Response deleted = api.delete(PRACTICE + "/sessions/" + session);

		assertThat(text.status()).isEqualTo(200);
		assertThat(voice.first("transcript").data().get("text").asString()).contains(spokenMark);
		assertThat(feedback).contains("READY");
		assertThat(tooLong.status()).isEqualTo(422);
		assertThat(tooLong.body()).doesNotContain(answer);
		assertThat(deleted.status()).isEqualTo(204);
		String logs = output.getAll();
		assertThat(logs).isNotEmpty().doesNotContain(name).doesNotContain(answer).doesNotContain(spokenMark)
				.doesNotContain(audioMark).doesNotContain(question).doesNotContain(student.email());
		assertThat(count("SELECT count(*) FROM audit_event WHERE details::text LIKE ? OR details::text LIKE ?",
				"%" + answer + "%", "%" + name + "%")).isZero();
	}

	@Test
	@DisplayName("012-07: la marca escondida en el audio no queda en ninguna tabla: el audio vive solo en memoria")
	void t012_07_audioIsNeverStored() {
		String audioMark = "oculto" + UUID.randomUUID().toString().replace("-", "");
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		start(student.api(), session);

		SseClient.Result voice = sse(student.api()).postAudio(DORA + session + "/turns",
				Wav.of(1000, false, "Respuesta por voz inventada.", audioMark), "audio/webm",
				java.util.Map.of("questionPosition", "1", "clientTurnId", UUID.randomUUID().toString()));

		assertThat(voice.status()).isEqualTo(200);
		for (String table : jdbc.queryForList("SELECT table_name FROM information_schema.tables "
				+ "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'", String.class)) {
			assertThat(count("SELECT count(*) FROM \"" + table + "\" t WHERE t::text LIKE ?", "%" + audioMark + "%"))
					.as("tabla %s", table).isZero();
		}
	}

	@Test
	@DisplayName("012-33: toString de puertos, peticiones y vistas no muestra contenido")
	void t012_33_toStringHidesContent() {
		String mark = "marca" + UUID.randomUUID().toString().replace("-", "");
		List<Object> values = List.of(
				new LlmPort.BridgeRequest(LlmPort.Purpose.PRACTICE, mark, mark, mark, 50),
				new LlmPort.EvaluationRequest(LlmPort.Purpose.INTERVIEW, mark, mark, List.of()),
				new LlmPort.PlanRequest(LlmPort.Purpose.PRACTICE, List.of(new LlmPort.BasisItem("skill:java", mark)), 2),
				new LlmPort.PlannedQuestion(mark, "skill:java"),
				new LlmPort.RawCriterion("CLARITY", 3, mark, List.of(mark)),
				new ConversationPort.InputAudio(mark.getBytes(), "audio/wav", 1000),
				new SpokenAudio("audio/wav", mark.getBytes(), 1000),
				new DoraService.TurnCommand(mark.getBytes(), mark, 1, UUID.randomUUID()),
				new Views.Answer(UUID.randomUUID(), 1, "TEXT", mark, mark, false, null, null),
				new Views.Question(1, mark, "AI", "AI", "skill:java", null),
				new Views.Criterion("CLARITY", "Claridad", 3, mark, List.of(mark)),
				new Views.Turn(UUID.randomUUID(), 1, 1, "TEXT", mark, mark, false, null),
				new InterviewResults.AnswerResult(1, mark, "AI", mark, mark, false, "TEXT", "READY", List.of()),
				new InterviewResults.CriterionResult("CLARITY", 3, mark, List.of(mark)));

		for (Object value : values) {
			assertThat(value.toString()).as(value.getClass().getSimpleName()).doesNotContain(mark);
		}
	}

}
