package com.talenti.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.talenti.identity.Role;
import com.talenti.practice.adapter.SimulatedRealtimeCalls;
import com.talenti.practice.adapter.Simulation;
import com.talenti.practice.service.RealtimeService;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;
import com.talenti.support.SseClient;

import tools.jackson.databind.JsonNode;

/**
 * DORA en tiempo real (spec 012, enmienda 2) de punta a punta con PostgreSQL real y el adaptador SIMULADO de
 * {@code RealtimeCallPort} (ninguna prueba sale a la red). El contexto enciende el tiempo real simulado, baja el tiempo
 * máximo de conexión a 1 s y usa un precio por minuto mínimo para no gastar el tope compartido por otras pruebas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"talenti.demo-data.enabled=true", "talenti.ai.dora.realtime.simulated=true",
		"talenti.ai.dora.realtime.connect-timeout=1s", "talenti.ai.dora.realtime.price-per-minute=0.001" })
class RealtimeIntegrationTests extends PracticeIntegrationTest {

	static final String SDP = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\n"
			+ "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\na=sendrecv\r\nm=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";

	private static final String NOTICE = "v2";

	@Autowired
	private SimulatedRealtimeCalls calls;

	@Autowired
	private RealtimeService realtime;

	@BeforeEach
	void resetRealtime() {
		calls.reset();
		realtime.resetLimits();
	}

	@AfterEach
	void closeOpenRealtime() {
		calls.reset();
	}

	// ------------------------------------------------------------------------------------------ piezas

	private static String realtimePath(String session) {
		return DORA + session + "/realtime";
	}

	private Response connect(ApiClient api, String session) {
		return api.post(realtimePath(session), body("sdp", SDP));
	}

	private JsonNode connected(ApiClient api, String session) {
		Response response = connect(api, session);
		assertThat(response.status()).as("conectar: %s", response.body()).isEqualTo(201);
		return response.json();
	}

	private Response events(ApiClient api, String session, String callId, List<Map<String, Object>> events) {
		Map<String, Object> request = new LinkedHashMap<>();
		request.put("callId", callId);
		request.put("events", events);
		return api.post(realtimePath(session) + "/events", request);
	}

	private JsonNode sent(ApiClient api, String session, String callId, List<Map<String, Object>> events) {
		Response response = events(api, session, callId, events);
		assertThat(response.status()).as("eventos: %s", response.body()).isEqualTo(200);
		return response.json();
	}

	private Response hangup(ApiClient api, String session, String callId, String reason) {
		return api.post(realtimePath(session) + "/hangup", body("callId", callId, "reason", reason));
	}

	private Map<String, Object> said(String speaker, String text) {
		return said(speaker, text, clock.instant(), "VOICE");
	}

	private static Map<String, Object> said(String speaker, String text, Instant at, String inputMode) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", "utterance");
		event.put("eventId", "u-" + UUID.randomUUID());
		event.put("speaker", speaker);
		event.put("text", text);
		event.put("inputMode", inputMode);
		event.put("occurredAt", at.toString());
		return event;
	}

	private static Map<String, Object> stage(String phase, Integer position) {
		return stage(phase, position, null);
	}

	private static Map<String, Object> stage(String phase, Integer position, Instant at) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("type", "stage");
		event.put("eventId", "s-" + UUID.randomUUID());
		event.put("phase", phase);
		if (position != null) {
			event.put("position", position);
		}
		if (at != null) {
			event.put("occurredAt", at.toString());
		}
		return event;
	}

	private static String callId(JsonNode call) {
		return call.get("callId").asString();
	}

	private String interviewSession(ApiClient api) {
		String application = apply(api);
		Response created = api.post("/api/v1/applications/" + application + "/interview",
				body("noticeAccepted", true, "noticeVersion", NOTICE));
		assertThat(created.status()).as(created.body()).isEqualTo(201);
		return id(created.json());
	}

	private List<String> interviewEvents(String session) {
		return jdbc.queryForList("SELECT kind FROM interview_event WHERE session_id = ?::uuid ORDER BY occurred_at, id",
				String.class, session);
	}

	private String turnText(String session, int position) {
		List<String> texts = jdbc.queryForList("SELECT t.transcript_final FROM practice_turn t JOIN practice_question q "
				+ "ON q.id = t.question_id WHERE q.session_id = ?::uuid AND q.position = ?", String.class, session, position);
		return texts.isEmpty() ? null : texts.get(0);
	}

	private String questionText(String session, int position) {
		return jdbc.queryForObject("SELECT text FROM practice_question WHERE session_id = ?::uuid AND position = ?",
				String.class, session, position);
	}

	// ------------------------------------------------------------------------------------------ 012-38, 012-39

	@Test
	@DisplayName("012-38: las opciones dicen el proveedor y si el tiempo real está disponible, sin llamar al proveedor")
	void t012_38_optionsShowRealtime() {
		RegisteredStudent student = studentWithProfile();

		JsonNode options = ok(student.api().get(PRACTICE + "/options"));

		assertThat(options.at("/dora/provider").asString()).isEqualTo("SIMULATED");
		assertThat(options.at("/dora/realtime/available").asBoolean()).isTrue();
		assertThat(options.at("/dora/realtime/reason").isNull()).isTrue();
		assertThat(calls.received()).isEmpty();
	}

	@Test
	@DisplayName("012-39: conectar responde 201 con la respuesta SDP, fin, aviso, etapa inicial y eventos; la sesión queda IN_PROGRESS en REALTIME con sus versiones y el SDP no se guarda")
	void t012_39_connect() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 3));
		String sdpMark = "marcasdp" + UUID.randomUUID().toString().replace("-", "");
		Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);

		Response response = student.api().post(realtimePath(session), body("sdp", SDP + "a=x-" + sdpMark + "\r\n"));

		assertThat(response.status()).as(response.body()).isEqualTo(201);
		assertThat(response.header("Cache-Control")).contains("no-store");
		JsonNode call = response.json();
		assertThat(call.get("sdp").asString()).isEqualTo(SimulatedRealtimeCalls.ANSWER_SDP);
		assertThat(Instant.parse(call.get("endsAt").asString())).isEqualTo(now.plus(Duration.ofMinutes(15)));
		assertThat(Instant.parse(call.get("warnAt").asString())).isEqualTo(now.plus(Duration.ofMinutes(13)));
		assertThat(call.get("startPhase").asString()).isEqualTo("GREETING");
		assertThat(call.get("startPosition").asInt()).isEqualTo(1);
		assertThat(call.at("/events/channel").asString()).isEqualTo("oai-events");
		assertThat(call.at("/events/personTranscript").asString())
				.isEqualTo("conversation.item.input_audio_transcription.completed");
		assertThat(call.at("/events/doraTranscript").asString()).isEqualTo("response.output_audio_transcript.done");
		for (String key : List.of("channel", "personTranscript", "doraTranscript", "doraTranscriptDelta", "speechStarted",
				"speechStopped", "outputAudioStarted", "outputAudioStopped", "functionCall", "responseDone", "itemCreate",
				"responseCreate")) {
			assertThat(call.at("/events/" + key).asString()).as("evento %s", key).isNotBlank();
		}
		assertThat(call.at("/events/responseCreate").asString()).isEqualTo("response.create");
		Map<String, Object> row = jdbc.queryForMap("SELECT status, channel, realtime_model, realtime_voice, "
				+ "transcription_model, prompt_version, ends_at FROM practice_session WHERE id = ?::uuid", session);
		assertThat(row.get("status")).isEqualTo("IN_PROGRESS");
		assertThat(row.get("channel")).isEqualTo("REALTIME");
		assertThat(row.get("realtime_model")).isEqualTo("simulated-realtime");
		assertThat(row.get("realtime_voice")).isEqualTo("simulated");
		assertThat(row.get("transcription_model")).isEqualTo("simulated-transcribe");
		assertThat(row.get("prompt_version")).isEqualTo("realtime-interviewer-v1");
		assertThat(jdbc.queryForObject("SELECT status FROM realtime_call WHERE id = ?::uuid", String.class,
				callId(call))).isEqualTo("OPEN");
		assertThat(count("SELECT count(*) FROM ai_usage_event WHERE read_id = ?::uuid AND function = 'PRACTICE_SESSION'",
				session)).as("cupo anotado al abrir la primera llamada").isEqualTo(1);
		assertThat(calls.received().get(0).sdp()).contains(sdpMark);
		for (String table : jdbc.queryForList("SELECT table_name FROM information_schema.tables "
				+ "WHERE table_schema = 'public' AND table_type = 'BASE TABLE'", String.class)) {
			assertThat(count("SELECT count(*) FROM \"" + table + "\" t WHERE t::text LIKE ?", "%" + sdpMark + "%"))
					.as("el SDP no queda en %s", table).isZero();
		}
	}

	@Test
	@DisplayName("012-41: a las instrucciones viajan el primer nombre y el plan del servidor en orden; nunca apellidos, correo ni teléfono")
	void t012_41_instructionsCarryOnlyFirstNameAndPlan() {
		String first = letters("Nom");
		String last = letters("Ape");
		RegisteredStudent student = studentWithProfile(first + " Segundo", last + " Inventado");
		String session = id(createPractice(student.api(), 3));

		connected(student.api(), session);

		String instructions = calls.received().get(0).instructions();
		assertThat(instructions).contains(first).doesNotContain("Segundo").doesNotContain(last)
				.doesNotContain(student.email()).doesNotContain("912345678").doesNotContain("@ejemplo.test");
		int previous = -1;
		for (int position = 1; position <= 3; position++) {
			int at = instructions.indexOf(questionText(session, position));
			assertThat(at).as("pregunta %s en orden", position).isGreaterThan(previous);
			previous = at;
		}
		assertThat(instructions).contains("cuéntame de ti").contains("¿tienes alguna pregunta para mí?");
	}

	// ------------------------------------------------------------------------------------------ 012-42, 012-43

	@Test
	@DisplayName("012-42: si el proveedor falla o no responde a tiempo, 503 realtime-unavailable con fallback TURN_BASED; la sesión no cambia ni consume cupo")
	void t012_42_providerFailureFallsBack() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));

		calls.createMode(Simulation.Mode.FAIL);
		Response failed = connect(student.api(), session);
		calls.createMode(Simulation.Mode.HANG);
		long started = System.nanoTime();
		Response hung = connect(student.api(), session);
		long hungMs = (System.nanoTime() - started) / 1_000_000;

		for (Response response : List.of(failed, hung)) {
			assertThat(response.status()).as(response.body()).isEqualTo(503);
			assertThat(response.json().get("code").asString()).isEqualTo("realtime-unavailable");
			assertThat(response.json().get("fallback").asString()).isEqualTo("TURN_BASED");
		}
		assertThat(hungMs).as("tiempo máximo de conexión (1 s en esta prueba)").isLessThan(4000);
		assertThat(sessionStatus(session)).isEqualTo("CREATED");
		assertThat(count("SELECT count(*) FROM ai_usage_event WHERE read_id = ?::uuid", session)).isZero();
		assertThat(jdbc.queryForList("SELECT status FROM realtime_call WHERE session_id = ?::uuid", String.class,
				session)).containsOnly("FAILED");
		calls.createMode(Simulation.Mode.NORMAL);
		assertThat(connect(student.api(), session).status()).as("las fallas no cuentan como reconexión").isEqualTo(201);
	}

	@Test
	@DisplayName("012-43: SDP grande 413, inválido 422, sin sesión 401, sin CSRF 403, otro rol 403, ajena 404, otra llamada abierta 409, más de 5 por minuto 429")
	void t012_43_validationAndSecurity() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		ApiClient api = student.api();

		Response tooLarge = api.post(realtimePath(session), body("sdp", SDP + "a=" + "x".repeat(33 * 1024) + "\r\n"));
		Response noVersion = api.post(realtimePath(session), body("sdp", SDP.replace("v=0", "v=9")));
		Response noAudio = api.post(realtimePath(session), body("sdp", SDP.replace("m=audio", "m=video")));
		Response anonymous = client().post(realtimePath(session), body("sdp", SDP));
		Response noCsrf = api.postWithoutCsrf(realtimePath(session), body("sdp", SDP));
		Response foreign = studentWithProfile().api().post(realtimePath(session), body("sdp", SDP));
		CreatedUser company = createUser(Role.COMPANY);
		ApiClient companyApi = client();
		companyApi.post("/api/v1/auth/login", loginBody(company.email(), company.password()));
		Response otherRole = companyApi.post(realtimePath(session), body("sdp", SDP));

		assertThat(tooLarge.status()).isEqualTo(413);
		assertThat(tooLarge.json().get("code").asString()).isEqualTo("sdp-too-large");
		assertThat(noVersion.status()).isEqualTo(422);
		assertThat(noVersion.json().get("code").asString()).isEqualTo("sdp-invalid");
		assertThat(noAudio.status()).isEqualTo(422);
		assertThat(anonymous.status()).isEqualTo(401);
		assertThat(noCsrf.status()).isEqualTo(403);
		assertThat(otherRole.status()).isEqualTo(403);
		assertThat(foreign.status()).isEqualTo(404);
		assertThat(foreign.json().get("code").asString()).isEqualTo("session-not-found");
		assertThat(calls.received()).as("nada llegó al proveedor").isEmpty();

		assertThat(connect(api, session).status()).isEqualTo(201);
		Response second = connect(api, session);
		assertThat(second.status()).isEqualTo(409);
		assertThat(second.json().get("code").asString()).isEqualTo("realtime-call-open");
		Response limited = connect(api, session);
		for (int i = 0; i < 3 && limited.status() != 429; i++) {
			limited = connect(api, session);
		}
		assertThat(limited.status()).as("la sexta conexión del minuto").isEqualTo(429);
	}

	// ------------------------------------------------------------------------------------------ 012-44, 012-45

	@Test
	@DisplayName("012-44: los eventos se guardan una sola vez por eventId; los inválidos se rechazan; llamada cerrada o ajena 409; más de 50 eventos 413")
	void t012_44_eventsAreIdempotent() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		String call = callId(connected(student.api(), session));
		List<Map<String, Object>> batch = List.of(stage("GREETING", null), said("DORA", "Hola, soy DORA."),
				said("PERSON", "Hola, gracias."));
		Map<String, Object> invalid = said("ALGUIEN", "No vale.");
		Map<String, Object> tooLong = said("PERSON", "x".repeat(4001));

		JsonNode first = sent(student.api(), session, call, batch);
		JsonNode again = sent(student.api(), session, call,
				List.of(batch.get(0), batch.get(1), batch.get(2), invalid, tooLong));
		List<Map<String, Object>> many = new ArrayList<>();
		for (int i = 0; i < 51; i++) {
			many.add(said("PERSON", "Uno más."));
		}
		Response tooMany = events(student.api(), session, call, many);
		Response wrongCall = events(student.api(), session, UUID.randomUUID().toString(), batch);
		Response foreign = events(studentWithProfile().api(), session, call, batch);

		assertThat(first.get("accepted").asInt()).isEqualTo(3);
		assertThat(again.get("accepted").asInt()).isZero();
		assertThat(again.get("duplicates").asInt()).isEqualTo(3);
		assertThat(again.get("rejected").asInt()).isEqualTo(2);
		assertThat(count("SELECT count(*) FROM practice_utterance WHERE session_id = ?::uuid", session)).isEqualTo(3);
		assertThat(tooMany.status()).isEqualTo(413);
		assertThat(wrongCall.status()).isEqualTo(409);
		assertThat(wrongCall.json().get("code").asString()).isEqualTo("session-closed");
		assertThat(foreign.status()).isEqualTo(404);

		assertThat(hangup(student.api(), session, call, "USER_LEFT").status()).isEqualTo(200);
		Response closedCall = events(student.api(), session, call, List.of(said("PERSON", "Tarde.")));
		assertThat(closedCall.status()).isEqualTo(409);
		assertThat(closedCall.json().get("code").asString()).isEqualTo("session-closed");
	}

	@Test
	@DisplayName("012-45 y 012-46: solo se aceptan avances; la respuesta de una pregunta se arma con la pregunta y su repregunta, al dejarla, y lo de la charla no se califica")
	void t012_45_46_stagesAndAssembly() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 3));
		String call = callId(connected(student.api(), session));

		JsonNode greeting = sent(student.api(), session, call, List.of(stage("GREETING", null),
				said("DORA", "Hola, soy DORA."), said("PERSON", "Hola."), stage("INTRO", null),
				said("PERSON", "Estudio computación en un instituto.")));
		JsonNode question1 = sent(student.api(), session, call, List.of(stage("QUESTION", 1),
				said("PERSON", "Usé Java con Spring Boot."), stage("FOLLOW_UP", 1), said("PERSON", "Y probé con JUnit."),
				stage("FOLLOW_UP", 1), stage("INTRO", null), stage("QUESTION", 9)));
		assertThat(turnText(session, 1)).as("todavía en la pregunta 1").isNull();
		JsonNode next = sent(student.api(), session, call, List.of(stage("QUESTION", 3), stage("QUESTION", 2),
				said("PERSON", "Aquí respondo la tercera.", clock.instant(), "TEXT")));

		assertThat(greeting.get("accepted").asInt()).isEqualTo(5);
		assertThat(question1.get("accepted").asInt()).isEqualTo(4);
		assertThat(question1.get("rejected").asInt()).as("segunda repregunta, atrás y fuera del plan").isEqualTo(3);
		assertThat(next.get("rejected").asInt()).as("volver a la 2").isEqualTo(1);
		assertThat(turnText(session, 1)).isEqualTo("Usé Java con Spring Boot. Y probé con JUnit.");
		assertThat(turnText(session, 2)).as("pregunta saltada: sin respuesta").isNull();
		List<String> rejectedStage = jdbc.queryForList("SELECT phase FROM practice_utterance WHERE session_id = ?::uuid "
				+ "AND speaker = 'PERSON' AND text = 'Aquí respondo la tercera.'", String.class, session);
		assertThat(rejectedStage).containsExactly("QUESTION");

		ok(student.api().post(DORA + session + "/finish", null));
		assertThat(turnText(session, 3)).as("se arma al cerrar").isEqualTo("Aquí respondo la tercera.");
		assertThat(jdbc.queryForObject("SELECT t.input_mode FROM practice_turn t JOIN practice_question q ON q.id = "
				+ "t.question_id WHERE q.session_id = ?::uuid AND q.position = 3", String.class, session)).isEqualTo("TEXT");
		assertThat(count("SELECT count(*) FROM practice_turn t JOIN practice_question q ON q.id = t.question_id "
				+ "WHERE q.session_id = ?::uuid", session)).isEqualTo(2);
	}

	@Test
	@DisplayName("012-46: una transcripción que llega tarde (antes del cambio de etapa) rearma la respuesta si no se corrigió")
	void t012_46_lateUtteranceRebuildsAnswer() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		String call = callId(connected(student.api(), session));
		Instant spoken = clock.instant();
		Instant moved = spoken.plusSeconds(5);

		sent(student.api(), session, call, List.of(stage("QUESTION", 1, spoken.minusSeconds(10)),
				said("PERSON", "Primera parte.", spoken, "VOICE"), stage("QUESTION", 2, moved)));
		String before = turnText(session, 1);
		sent(student.api(), session, call, List.of(said("PERSON", "Parte que llegó tarde.", spoken.plusSeconds(2),
				"VOICE")));

		assertThat(before).isEqualTo("Primera parte.");
		assertThat(turnText(session, 1)).isEqualTo("Primera parte. Parte que llegó tarde.");
		String turn = jdbc.queryForObject("SELECT t.id::text FROM practice_turn t JOIN practice_question q ON "
				+ "q.id = t.question_id WHERE q.session_id = ?::uuid AND q.position = 1", String.class, session);
		ok(student.api().writeWithToken("PATCH", DORA + session + "/turns/" + turn, body("transcript", "Corregida."),
				student.api().cookie("XSRF-TOKEN")));
		sent(student.api(), session, call, List.of(said("PERSON", "Otra tardía.", spoken.plusSeconds(3), "VOICE")));
		assertThat(turnText(session, 1)).as("corregida: no se rearma").isEqualTo("Corregida.");
	}

	// ------------------------------------------------------------------------------------------ 012-47, 012-48

	@Test
	@DisplayName("012-47 y 012-18: la práctica evalúa la respuesta armada con la pregunta del servidor; nunca la transcripción de DORA ni el nombre")
	void t012_47_practiceFeedbackWithoutDoraOrName() {
		String name = letters("Nm");
		String doraMark = "doramarca" + UUID.randomUUID().toString().replace("-", "");
		RegisteredStudent student = studentWithProfile(name, "Apellido Inventado");
		String session = id(createPractice(student.api(), 2));
		String call = callId(connected(student.api(), session));
		llm.reset();

		sent(student.api(), session, call, List.of(stage("QUESTION", 1),
				said("DORA", "Hola " + name + ", pregunta falsa " + doraMark),
				said("PERSON", "Separé el código en servicios con Java."), stage("QUESTION", 2)));
		String turn = jdbc.queryForObject("SELECT t.id::text FROM practice_turn t JOIN practice_question q ON "
				+ "q.id = t.question_id WHERE q.session_id = ?::uuid AND q.position = 1", String.class, session);
		awaitFeedbackDone(turn);

		assertThat(feedbackStatus(turn)).isEqualTo("READY");
		String evaluation = llm.journal().stream().filter(r -> r.startsWith("EVALUATE")).findFirst().orElseThrow();
		assertThat(evaluation).contains(questionText(session, 1)).contains("Separé el código en servicios con Java.")
				.doesNotContain(doraMark).doesNotContain(name);
		JsonNode detail = ok(student.api().get(PRACTICE + "/sessions/" + session));
		JsonNode dora = null;
		for (JsonNode line : detail.get("conversation")) {
			if (line.get("speaker").asString().equals("DORA")) {
				dora = line;
			}
		}
		assertThat(dora).isNotNull();
		assertThat(dora.get("generatedBy").asString()).isEqualTo("AI");
		assertThat(dora.get("reportedBy").asString()).isEqualTo("CLIENT");
		assertThat(detail.get("channel").asString()).isEqualTo("REALTIME");
		assertThat(detail.at("/versions/realtimeModel").asString()).isEqualTo("simulated-realtime");
	}

	@Test
	@DisplayName("012-45, 012-47 y 012-25: en la entrevista la repregunta deja FOLLOW_UP_ASKED, al terminar se evalúa sin el nombre y la postulación pasa a INTERVIEW_DONE")
	void t012_47_interviewEvaluation() {
		String name = letters("Nm");
		RegisteredStudent student = studentWithProfile(name, "Apellido Inventado");
		String session = interviewSession(student.api());
		String application = jdbc.queryForObject("SELECT application_id::text FROM practice_session WHERE id = ?::uuid",
				String.class, session);
		String call = callId(connected(student.api(), session));
		llm.reset();

		sent(student.api(), session, call, List.of(stage("GREETING", null), said("DORA", "Hola " + name + "."),
				stage("QUESTION", 1), said("PERSON", "Uso Java a diario."), stage("FOLLOW_UP", 1),
				said("PERSON", "Con pruebas unitarias."), stage("FOLLOW_UP", 1), stage("QUESTION", 2),
				said("PERSON", "Uso SQL con índices."), stage("CANDIDATE_QUESTIONS", null),
				said("PERSON", "¿Cuándo sabré el resultado?"), stage("CLOSING", null)));
		assertThat(hangup(student.api(), session, call, "FINISHED").status()).isEqualTo(200);
		ok(student.api().post(DORA + session + "/finish", null));
		await("evaluación de la entrevista", () -> count("SELECT count(*) FROM practice_feedback f JOIN practice_turn t "
				+ "ON t.id = f.turn_id JOIN practice_question q ON q.id = t.question_id WHERE q.session_id = ?::uuid "
				+ "AND f.status <> 'PENDING'", session) == 2);

		assertThat(applicationStatus(application)).isEqualTo("INTERVIEW_DONE");
		assertThat(interviewEvents(session)).contains("NOTICE_ACCEPTED", "STARTED", "FOLLOW_UP_ASKED", "FINISHED");
		assertThat(interviewEvents(session).stream().filter("FOLLOW_UP_ASKED"::equals).count()).isEqualTo(1);
		assertThat(interviewEvents(session).stream().filter("ANSWERED"::equals).count()).isEqualTo(2);
		assertThat(turnText(session, 1)).isEqualTo("Uso Java a diario. Con pruebas unitarias.");
		assertThat(llm.journal().stream().filter(r -> r.startsWith("EVALUATE purpose=INTERVIEW")).toList()).hasSize(2)
				.allSatisfy(r -> assertThat(r).doesNotContain(name).doesNotContain("¿Cuándo sabré"));
		assertThat(count("SELECT count(*) FROM ai_usage_event WHERE read_id = ?::uuid AND function = 'PRACTICE_SESSION'",
				session)).as("la entrevista no usa cupo personal").isZero();
		JsonNode view = ok(student.api().get("/api/v1/applications/" + application + "/interview"));
		assertThat(view.get("conversation").size()).isEqualTo(5);
		assertThat(view.get("channel").asString()).isEqualTo("REALTIME");
	}

	// ------------------------------------------------------------------------------------------ 012-49, 012-50

	@Test
	@DisplayName("012-49 y 012-50: en endsAt + gracia el servidor cuelga, la sesión queda COMPLETED con TIME_LIMIT y se anota DORA_REALTIME con los segundos del servidor")
	void t012_49_timeLimitWithServerClock() {
		RegisteredStudent student = studentWithProfile();
		String session = interviewSession(student.api());
		String call = callId(connected(student.api(), session));
		sent(student.api(), session, call, List.of(stage("QUESTION", 1), said("PERSON", "Respuesta inventada.")));

		clock.advance(Duration.ofMinutes(20).plusSeconds(20));
		realtime.sweepTimeLimits();
		assertThat(sessionStatus(session)).as("todavía dentro de la gracia").isEqualTo("IN_PROGRESS");
		clock.advance(Duration.ofSeconds(60));
		realtime.sweepTimeLimits();

		assertThat(sessionStatus(session)).isEqualTo("COMPLETED");
		assertThat(jdbc.queryForMap("SELECT status, end_reason, measured_seconds FROM realtime_call WHERE id = ?::uuid",
				call)).containsEntry("status", "CLOSED").containsEntry("end_reason", "TIME_LIMIT")
				.containsEntry("measured_seconds", 20 * 60 + 30);
		assertThat(interviewEvents(session)).contains("TIME_LIMIT", "FINISHED");
		String providerCall = jdbc.queryForObject("SELECT provider_call_id FROM realtime_call WHERE id = ?::uuid", String.class, call);
		await("colgar en el proveedor", () -> calls.hangups().contains(providerCall));
		Map<String, Object> usage = jdbc.queryForMap("SELECT function, input_size, estimated_cost_micros FROM "
				+ "ai_usage_event WHERE read_id = ?::uuid", call);
		assertThat(usage.get("function")).isEqualTo("DORA_REALTIME");
		assertThat(((Number) usage.get("input_size")).intValue()).isEqualTo(1230);
		assertThat(((Number) usage.get("estimated_cost_micros")).longValue()).as("1230 s × 0,001 USD por minuto").isEqualTo(20_500);
		assertThat(turnText(session, 1)).isEqualTo("Respuesta inventada.");
		Response late = events(student.api(), session, call, List.of(said("PERSON", "Tarde.")));
		assertThat(late.status()).isEqualTo(409);
	}

	@Test
	@DisplayName("012-49: con el gasto agotado, las opciones dicen SPEND_LIMIT y conectar da 503 realtime-unavailable con ese motivo")
	void t012_49_spendLimit() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		UUID costly = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO ai_usage_event (id, occurred_at, function, level, adapter, outcome, read_id, estimated_cost_micros)
				VALUES (?, ?, 'DORA_DIALOG', 'BASIC', 'prueba', 'SUCCESS', ?, 100000000)""", UUID.randomUUID(),
				java.sql.Timestamp.from(clock.instant()), costly);
		try {
			JsonNode options = ok(student.api().get(PRACTICE + "/options"));
			Response refused = connect(student.api(), session);

			assertThat(options.at("/dora/realtime/available").asBoolean()).isFalse();
			assertThat(options.at("/dora/realtime/reason").asString()).isEqualTo("SPEND_LIMIT");
			assertThat(refused.status()).isEqualTo(503);
			assertThat(refused.json().get("reason").asString()).isEqualTo("SPEND_LIMIT");
			assertThat(refused.json().get("fallback").asString()).isEqualTo("TURN_BASED");
			assertThat(calls.received()).isEmpty();
		}
		finally {
			jdbc.update("DELETE FROM ai_usage_event WHERE read_id = ?", costly);
		}
	}

	@Test
	@DisplayName("012-50: al colgar se anota una fila DORA_REALTIME con los segundos medidos por el servidor, sin contenido")
	void t012_50_usageOnHangup() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		String call = callId(connected(student.api(), session));
		clock.advance(Duration.ofSeconds(90));

		Response response = hangup(student.api(), session, call, "FINISHED");
		Response repeated = hangup(student.api(), session, call, "FINISHED");

		assertThat(response.status()).isEqualTo(200);
		assertThat(repeated.status()).as("colgar dos veces es inofensivo").isEqualTo(200);
		assertThat(response.json().at("/session/status").asString()).isEqualTo("IN_PROGRESS");
		Map<String, Object> usage = jdbc.queryForMap("SELECT function, input_size, output_size, model, "
				+ "estimated_cost_micros FROM ai_usage_event WHERE read_id = ?::uuid", call);
		assertThat(usage.get("function")).isEqualTo("DORA_REALTIME");
		assertThat(((Number) usage.get("input_size")).intValue()).isEqualTo(90);
		assertThat(((Number) usage.get("estimated_cost_micros")).longValue()).isEqualTo(1_500);
		assertThat(count("SELECT count(*) FROM ai_usage_event WHERE read_id = ?::uuid", call)).isEqualTo(1);
	}

	// ------------------------------------------------------------------------------------------ 012-51 a 012-53

	@Test
	@DisplayName("012-51: retirar la postulación, la inactividad y un reinicio cierran la llamada y la cuelgan sin bloquear")
	void t012_51_otherClosesHangUp() {
		RegisteredStudent student = studentWithProfile();
		String interview = interviewSession(student.api());
		String application = jdbc.queryForObject("SELECT application_id::text FROM practice_session WHERE id = ?::uuid",
				String.class, interview);
		String interviewCall = callId(connected(student.api(), interview));
		ok(student.api().post("/api/v1/applications/" + application + "/withdraw", null));
		assertThat(sessionStatus(interview)).isEqualTo("ABANDONED");
		assertThat(jdbc.queryForObject("SELECT status FROM realtime_call WHERE id = ?::uuid", String.class,
				interviewCall)).isEqualTo("CLOSED");

		RegisteredStudent idle = studentWithProfile();
		String idleSession = id(createPractice(idle.api(), 2));
		String idleCall = callId(connected(idle.api(), idleSession));
		clock.advance(Duration.ofMinutes(16));
		lifecycle.sweepInactive();
		assertThat(sessionStatus(idleSession)).isEqualTo("ABANDONED");
		assertThat(jdbc.queryForObject("SELECT measured_seconds FROM realtime_call WHERE id = ?::uuid", Integer.class,
				idleCall)).as("acotado al fin + gracia").isEqualTo(15 * 60 + 30);

		clock.set(Instant.now());
		RegisteredStudent restarted = studentWithProfile();
		String restartedSession = id(createPractice(restarted.api(), 2));
		String restartedCall = callId(connected(restarted.api(), restartedSession));
		sent(restarted.api(), restartedSession, restartedCall, List.of(said("PERSON", "Hola.")));
		clock.advance(Duration.ofMinutes(5));
		lifecycle.recoverAfterRestart();
		assertThat(sessionStatus(restartedSession)).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("SELECT measured_seconds FROM realtime_call WHERE id = ?::uuid", Integer.class,
				restartedCall)).as("hasta la última actividad").isLessThan(10);

		await("colgar las tres", () -> calls.hangups().size() >= 3);
	}

	@Test
	@DisplayName("012-52: tras un corte se reconecta en RESUME desde la primera pregunta sin respuesta, hasta max-reconnects; con DISCONNECTED y RESUMED")
	void t012_52_reconnect() {
		RegisteredStudent student = studentWithProfile();
		String session = interviewSession(student.api());
		JsonNode first = connected(student.api(), session);
		sent(student.api(), session, callId(first), List.of(stage("QUESTION", 1), said("PERSON", "Uno."),
				stage("QUESTION", 2)));
		assertThat(hangup(student.api(), session, callId(first), "ERROR").status()).isEqualTo(200);

		JsonNode second = connected(student.api(), session);
		Response replaced = student.api().post(realtimePath(session),
				body("sdp", SDP, "replacesCallId", callId(second)));
		Response blocked = connect(student.api(), session);
		assertThat(hangup(student.api(), session, callId(replaced.json()), "ERROR").status()).isEqualTo(200);
		Response exhausted = connect(student.api(), session);

		assertThat(second.get("startPhase").asString()).isEqualTo("RESUME");
		assertThat(second.get("startPosition").asInt()).isEqualTo(2);
		assertThat(Instant.parse(second.get("endsAt").asString())).isEqualTo(Instant.parse(first.get("endsAt").asString()));
		assertThat(calls.received().get(1).instructions()).contains("retomemos").contains("pregunta 2 del plan");
		assertThat(replaced.status()).as(replaced.body()).isEqualTo(201);
		assertThat(blocked.status()).isEqualTo(409);
		assertThat(exhausted.status()).isEqualTo(409);
		assertThat(exhausted.json().get("code").asString()).isEqualTo("realtime-reconnects-exhausted");
		assertThat(interviewEvents(session).stream().filter("DISCONNECTED"::equals).count()).isEqualTo(3);
		assertThat(interviewEvents(session).stream().filter("RESUMED"::equals).count()).isEqualTo(2);
	}

	@Test
	@DisplayName("012-53: con FALLBACK la sesión sigue IN_PROGRESS por turnos desde nextPosition, con CHANNEL_FALLBACK y los turnos armados conservados")
	void t012_53_fallbackToTurns() {
		RegisteredStudent student = studentWithProfile();
		String session = interviewSession(student.api());
		String call = callId(connected(student.api(), session));
		sent(student.api(), session, call, List.of(stage("QUESTION", 1), said("PERSON", "Respuesta por voz."),
				stage("QUESTION", 2)));
		SseClient.Result whileOpen = textTurn(student.api(), session, 2, "No todavía.");

		Response fallback = hangup(student.api(), session, call, "FALLBACK");
		SseClient.Result turn = textTurn(student.api(), session, 2, "Respuesta por escrito en el modo por turnos.");

		assertThat(whileOpen.status()).isEqualTo(409);
		assertThat(whileOpen.problem().get("code").asString()).isEqualTo("realtime-call-open");
		assertThat(fallback.status()).isEqualTo(200);
		assertThat(fallback.json().get("nextPosition").asInt()).isEqualTo(2);
		assertThat(fallback.json().at("/session/status").asString()).isEqualTo("IN_PROGRESS");
		assertThat(fallback.json().at("/session/channel").asString()).isEqualTo("TURN_BASED");
		assertThat(interviewEvents(session)).contains("CHANNEL_FALLBACK");
		assertThat(turn.status()).isEqualTo(200);
		assertThat(turn.first("turn.done").data().get("nextPosition").asInt()).isEqualTo(3);
		assertThat(turnText(session, 1)).isEqualTo("Respuesta por voz.");
		assertThat(turnText(session, 2)).isEqualTo("Respuesta por escrito en el modo por turnos.");
	}

	// ------------------------------------------------------------------------------------------ 012-54, 012-55

	@Test
	@DisplayName("012-54: el 409 session-open trae openSession de la misma persona y GET /practice/sessions/open responde 200 o 204")
	void t012_54_openSession() {
		RegisteredStudent student = studentWithProfile();
		assertThat(student.api().get(PRACTICE + "/sessions/open").status()).isEqualTo(204);
		String interview = interviewSession(student.api());

		Response conflict = student.api().post(PRACTICE + "/sessions",
				body("basis", "PROFILE", "questionCount", 2, "voice", true));
		Response open = student.api().get(PRACTICE + "/sessions/open");

		assertThat(conflict.status()).isEqualTo(409);
		assertThat(conflict.json().get("code").asString()).isEqualTo("session-open");
		assertThat(conflict.json().at("/openSession/id").asString()).isEqualTo(interview);
		assertThat(conflict.json().at("/openSession/mode").asString()).isEqualTo("INTERVIEW");
		assertThat(conflict.json().at("/openSession/status").asString()).isEqualTo("CREATED");
		assertThat(conflict.json().at("/openSession/channel").asString()).isEqualTo("TURN_BASED");
		assertThat(conflict.json().at("/openSession/applicationId").asString()).isNotBlank();
		assertThat(open.status()).isEqualTo(200);
		assertThat(open.json().get("id").asString()).isEqualTo(interview);
		assertThat(open.header("Cache-Control")).contains("no-store");
	}

	@Test
	@DisplayName("012-55: GET /admin/dora/latency?channel=REALTIME devuelve p50 y p95 de la latencia que reporta el navegador")
	void t012_55_latencyByChannel() {
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));
		String call = callId(connected(student.api(), session));
		List<Map<String, Object>> lines = new ArrayList<>();
		for (int i = 1; i <= 4; i++) {
			Map<String, Object> line = said("DORA", "Frase " + i + ".");
			line.put("latencyMs", 900_000 + i);
			lines.add(line);
		}
		lines.get(0).put("latencyMs", 400);
		sent(student.api(), session, call, lines);
		CreatedUser admin = createUser(Role.ADMIN);
		ApiClient api = client();
		api.post("/api/v1/auth/login", loginBody(admin.email(), admin.password()));

		JsonNode latency = ok(api.get("/api/v1/admin/dora/latency?channel=REALTIME&last=1"));
		Response bad = api.get("/api/v1/admin/dora/latency?channel=OTRO");

		assertThat(latency.get("count").asInt()).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM practice_utterance WHERE session_id = ?::uuid AND latency_ms IS NOT NULL",
				session)).as("latencias absurdas se ignoran").isEqualTo(1);
		assertThat(bad.status()).isEqualTo(422);
	}

}
