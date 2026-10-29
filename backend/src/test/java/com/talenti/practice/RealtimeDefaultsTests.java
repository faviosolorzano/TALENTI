package com.talenti.practice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.talenti.practice.adapter.SimulatedRealtimeCalls;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/**
 * 012-38 y 012-56 con la configuración POR DEFECTO: DORA usa simulados y el tiempo real no está disponible; conectar
 * responde 503 con el respaldo por turnos y no llega nada al adaptador.
 */
class RealtimeDefaultsTests extends PracticeIntegrationTest {

	private static final String SDP = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n";

	@Autowired
	private SimulatedRealtimeCalls calls;

	@Test
	@DisplayName("012-38: por defecto provider SIMULATED y tiempo real no disponible (DISABLED); conectar da 503 con fallback TURN_BASED")
	void t012_38_disabledByDefault() {
		calls.reset();
		RegisteredStudent student = studentWithProfile();
		String session = id(createPractice(student.api(), 2));

		JsonNode options = ok(student.api().get(PRACTICE + "/options"));
		Response refused = student.api().post(DORA + session + "/realtime", body("sdp", SDP));
		Response events = student.api().post(DORA + session + "/realtime/events",
				body("callId", UUID.randomUUID().toString(), "events", java.util.List.of()));

		assertThat(options.at("/dora/provider").asString()).isEqualTo("SIMULATED");
		assertThat(options.at("/dora/realtime/available").asBoolean()).isFalse();
		assertThat(options.at("/dora/realtime/reason").asString()).isEqualTo("DISABLED");
		assertThat(refused.status()).isEqualTo(503);
		assertThat(refused.json().get("code").asString()).isEqualTo("realtime-unavailable");
		assertThat(refused.json().get("reason").asString()).isEqualTo("DISABLED");
		assertThat(refused.json().get("fallback").asString()).isEqualTo("TURN_BASED");
		assertThat(events.status()).as("sin llamada abierta").isEqualTo(409);
		assertThat(sessionStatus(session)).isEqualTo("CREATED");
		assertThat(calls.received()).isEmpty();
	}

}
