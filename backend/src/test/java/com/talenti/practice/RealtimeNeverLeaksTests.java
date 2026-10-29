package com.talenti.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.sun.net.httpserver.HttpServer;
import com.talenti.matching.MatchingIntegrationTest;
import com.talenti.practice.port.RealtimeCallPort;
import com.talenti.practice.service.RealtimeService;
import com.talenti.practice.service.Views;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/**
 * 012-39 y 012-57: con el adaptador de OpenAI ENCENDIDO contra un servidor LOCAL falso (nunca la API real) y una
 * clave-marca, la clave solo viaja en la cabecera {@code Authorization} del servidor al "proveedor": nunca en la
 * respuesta al navegador, en sus cabeceras ni en los registros. Con el registro al máximo, tampoco aparecen el SDP, las
 * instrucciones (nombre y plan) ni lo dicho. Todos los datos son inventados.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"talenti.demo-data.enabled=true", "talenti.test.context=realtime-never-leaks",
		"talenti.ai.dora.enabled=true", "talenti.ai.dora.models.dialog=modelo-falso",
		"talenti.ai.dora.models.stt=stt-falso", "talenti.ai.dora.models.tts=tts-falso",
		"talenti.ai.dora.voice=voz-falsa", "talenti.ai.dora.realtime.enabled=true",
		"talenti.ai.dora.realtime.price-per-minute=0.001", "talenti.dora.question-timeout=2s",
		"logging.level.com.talenti=TRACE", "logging.level.org.springframework.web=TRACE",
		"logging.level.org.springframework.security=DEBUG", "logging.level.org.springframework.jdbc=DEBUG",
		"spring.mvc.log-request-details=true" })
class RealtimeNeverLeaksTests extends MatchingIntegrationTest {

	static final String KEY = "sk-marca-clave-" + UUID.randomUUID().toString().replace("-", "");

	static final List<String> AUTHORIZATIONS = Collections.synchronizedList(new ArrayList<>());

	static final List<String> BODIES = Collections.synchronizedList(new ArrayList<>());

	static final HttpServer PROVIDER = fakeProvider();

	/** El "proveedor" local: responde un SDP fijo y una cabecera Location; anota lo recibido. Lo demás, 404. */
	private static HttpServer fakeProvider() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/", exchange -> {
				String path = exchange.getRequestURI().getPath();
				AUTHORIZATIONS.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
				byte[] body = exchange.getRequestBody().readAllBytes();
				int status;
				byte[] answer = new byte[0];
				if (path.endsWith("/realtime/calls")) {
					BODIES.add(new String(body, StandardCharsets.UTF_8));
					answer = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=falso\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n"
							.getBytes(StandardCharsets.UTF_8);
					exchange.getResponseHeaders().add("Location", "/v1/realtime/calls/rtc_falso123");
					exchange.getResponseHeaders().add("Content-Type", "application/sdp");
					status = 201;
				}
				else if (path.endsWith("/hangup")) {
					status = 200;
				}
				else {
					status = 404;
				}
				exchange.sendResponseHeaders(status, answer.length == 0 ? -1 : answer.length);
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(answer);
				}
			});
			server.start();
			return server;
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	@DynamicPropertySource
	static void provider(DynamicPropertyRegistry registry) {
		String base = "http://127.0.0.1:" + PROVIDER.getAddress().getPort() + "/v1";
		registry.add("talenti.ai.openai.api-key", () -> KEY);
		registry.add("talenti.ai.openai.base-url", () -> base);
		registry.add("talenti.ai.dora.realtime.base-url", () -> base);
	}

	@AfterAll
	static void stop() {
		PROVIDER.stop(0);
	}

	private static String letters(String prefix) {
		StringBuilder mark = new StringBuilder(prefix);
		java.util.Random random = new java.util.Random();
		for (int i = 0; i < 10; i++) {
			mark.append((char) ('a' + random.nextInt(26)));
		}
		return mark.toString();
	}

	private static Map<String, Object> event(Object... keysAndValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			map.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}

	@Test
	@DisplayName("012-39 y 012-57: la clave solo viaja en la cabecera al proveedor; SDP, instrucciones, nombre y lo dicho no quedan en respuestas ni registros")
	void t012_57_nothingLeaks(CapturedOutput output) {
		String name = letters("Nm");
		String sdpMark = "sdpmarca" + UUID.randomUUID().toString().replace("-", "");
		String spoken = "dichomarca" + UUID.randomUUID().toString().replace("-", "");
		String doraLine = "doramarca" + UUID.randomUUID().toString().replace("-", "");
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		ok(api.put(PROFILE + "/skills", skills(skill("java", 2))));
		ok(api.put(PROFILE + "/about", body("firstNames", name, "lastNames", "Apellido Inventado", "contactEmail",
				"contacto.inventado@ejemplo.test", "phone", "912345678", "about", "Datos inventados.")));
		Response created = api.post("/api/v1/practice/sessions", body("basis", "PROFILE", "questionCount", 2,
				"voice", false));
		assertThat(created.status()).as(created.body()).isEqualTo(201);
		String session = created.json().get("id").asString();
		String question = jdbc.queryForObject("SELECT text FROM practice_question WHERE session_id = ?::uuid "
				+ "AND position = 1", String.class, session);
		String sdp = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=x-" + sdpMark
				+ "\r\n";

		Response connected = api.post("/api/v1/dora/sessions/" + session + "/realtime", body("sdp", sdp));
		assertThat(connected.status()).as(connected.body()).isEqualTo(201);
		String callId = connected.json().get("callId").asString();
		Response events = api.post("/api/v1/dora/sessions/" + session + "/realtime/events", body("callId", callId,
				"events", List.of(event("type", "stage", "eventId", "e1", "phase", "QUESTION", "position", 1),
						event("type", "utterance", "eventId", "e2", "speaker", "DORA", "text", doraLine),
						event("type", "utterance", "eventId", "e3", "speaker", "PERSON", "text", spoken),
						event("type", "stage", "eventId", "e4", "phase", "QUESTION", "position", 2))));
		Response hangup = api.post("/api/v1/dora/sessions/" + session + "/realtime/hangup",
				body("callId", callId, "reason", "FINISHED"));
		ok(api.post("/api/v1/dora/sessions/" + session + "/finish", null));

		assertThat(events.status()).as(events.body()).isEqualTo(200);
		assertThat(hangup.status()).isEqualTo(200);
		assertThat(AUTHORIZATIONS).contains("Bearer " + KEY);
		String sent = BODIES.get(BODIES.size() - 1);
		assertThat(sent).contains(sdpMark).contains(name).contains("gpt-realtime-2.1").contains("marin");
		for (Response response : List.of(created, connected, events, hangup)) {
			assertThat(response.body()).doesNotContain(KEY);
			assertThat(response.headers().toString()).doesNotContain(KEY);
		}
		assertThat(connected.body()).doesNotContain(sdpMark).doesNotContain(name);
		assertThat(connected.json().get("sdp").asString()).startsWith("v=0");
		String logs = output.getAll();
		assertThat(logs).isNotEmpty().doesNotContain(KEY).doesNotContain(sdpMark).doesNotContain(name)
				.doesNotContain(spoken).doesNotContain(doraLine).doesNotContain(question)
				.doesNotContain("cuéntame de ti");
		assertThat(count("SELECT count(*) FROM audit_event WHERE details::text LIKE ? OR details::text LIKE ?",
				"%" + spoken + "%", "%" + name + "%")).isZero();
	}

	@Test
	@DisplayName("012-57: toString de peticiones, filas y vistas del tiempo real no muestra SDP, instrucciones ni lo dicho")
	void t012_57_toStringHidesContent() {
		String mark = "marca" + UUID.randomUUID().toString().replace("-", "");
		List<Object> values = List.of(new RealtimeCallPort.CallRequest(mark, mark),
				new RealtimeCallPort.CreatedCall("rtc_1", mark),
				new Views.RealtimeCall(UUID.randomUUID(), mark, null, null, "GREETING", 1, null),
				new Views.Utterance("PERSON", mark, "QUESTION", 1, "VOICE", null, "CLIENT", null),
				new RealtimeService.EventInput("utterance", "e1", "PERSON", mark, "VOICE", null, null, null, null),
				new com.talenti.practice.InterviewResults.Utterance("PERSON", mark, "QUESTION", 1, "VOICE", null,
						"CLIENT", null),
				new com.talenti.practice.persistence.PracticeRepository.NewUtterance("e1", "UTTERANCE", "PERSON",
						"QUESTION", 1, mark, "VOICE", null, null));

		for (Object value : values) {
			assertThat(value.toString()).as(value.getClass().getSimpleName()).doesNotContain(mark);
		}
	}

}
