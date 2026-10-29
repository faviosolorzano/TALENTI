package com.talenti.practice.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.practice.port.AiCallException;
import com.talenti.practice.port.RealtimeCallPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 012-56 y 012-40: la suite de contrato contra OpenAI con un transporte simulado (sin red), y qué viaja. */
class OpenAiRealtimeCallContractTests extends RealtimeCallContractTests {

	/** Transporte falso: guarda lo enviado y responde como lo haría la API (SDP y cabecera Location). */
	static class FakeTransport implements RealtimeTransport {

		final boolean failing;

		final List<String> urls = Collections.synchronizedList(new ArrayList<>());

		final List<Map<String, String>> bodies = Collections.synchronizedList(new ArrayList<>());

		FakeTransport(boolean failing) {
			this.failing = failing;
		}

		@Override
		public Response postMultipart(String url, Map<String, String> fields, Duration timeout) throws IOException {
			urls.add(url);
			bodies.add(Map.copyOf(fields));
			if (failing) {
				return new Response(500, "{\"error\":\"" + fields.get("session") + "\"}", null);
			}
			return new Response(201, SimulatedRealtimeCalls.ANSWER_SDP, "/v1/realtime/calls/rtc_abc123");
		}

		@Override
		public int post(String url, Duration timeout) throws IOException {
			urls.add(url);
			if (failing) {
				throw new IOException("sin red");
			}
			return 200;
		}

	}

	static OpenAiRealtimeCalls.Settings settings(String baseUrl, String callsPath, String hangupPath, String model,
			String voice) {
		return new OpenAiRealtimeCalls.Settings(baseUrl, callsPath, hangupPath, model, voice, "gpt-4o-mini-transcribe",
				"es", "semantic_vad", Duration.ofSeconds(8), EVENTS);
	}


	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Override
	protected RealtimeCallPort port(boolean failing) {
		return new OpenAiRealtimeCalls(settings("https://api.openai.com/v1", "/realtime/calls",
				"/realtime/calls/{callId}/hangup", "gpt-realtime-2.1", "marin"), new FakeTransport(failing), JSON);
	}

	@Test
	@DisplayName("012-40: el pedido es multipart con el SDP sin cambios y la sesión con modelo, instrucciones, transcripción, turnos con interrupción, voz y herramientas")
	void t012_40_multipartAndSession() {
		FakeTransport transport = new FakeTransport(false);
		OpenAiRealtimeCalls port = new OpenAiRealtimeCalls(settings("https://api.openai.com/v1", "/realtime/calls",
				"/realtime/calls/{callId}/hangup", "gpt-realtime-2.1", "marin"), transport, JSON);

		RealtimeCallPort.CreatedCall call = port.createCall(new RealtimeCallPort.CallRequest(OFFER, "Guion de DORA."));
		port.hangup(call.providerCallId());

		assertThat(transport.urls).containsExactly("https://api.openai.com/v1/realtime/calls",
				"https://api.openai.com/v1/realtime/calls/rtc_abc123/hangup");
		Map<String, String> body = transport.bodies.get(0);
		assertThat(body.keySet()).containsExactlyInAnyOrder("sdp", "session");
		assertThat(body.get("sdp")).isEqualTo(OFFER);
		JsonNode session = JSON.readTree(body.get("session"));
		assertThat(session.path("type").asString()).isEqualTo("realtime");
		assertThat(session.path("model").asString()).isEqualTo("gpt-realtime-2.1");
		assertThat(session.path("instructions").asString()).isEqualTo("Guion de DORA.");
		assertThat(session.at("/audio/input/transcription/model").asString()).isEqualTo("gpt-4o-mini-transcribe");
		assertThat(session.at("/audio/input/turn_detection/type").asString()).isEqualTo("semantic_vad");
		assertThat(session.at("/audio/input/turn_detection/interrupt_response").asBoolean()).isTrue();
		assertThat(session.at("/audio/output/voice").asString()).isEqualTo("marin");
		List<String> tools = new ArrayList<>();
		session.path("tools").forEach(tool -> tools.add(tool.path("name").asString()));
		assertThat(tools).containsExactly("set_stage", "end_conversation");
		assertThat(session.at("/tools/0/parameters/properties/phase/enum").toString()).contains("FOLLOW_UP",
				"CANDIDATE_QUESTIONS");
		assertThat(body.toString()).as("la clave nunca va en el cuerpo").doesNotContain("Bearer");
		assertThat(call.providerCallId()).isEqualTo("rtc_abc123");
	}

	@Test
	@DisplayName("012-40: URL, rutas, modelo, voz y nombres de eventos salen de la configuración")
	void t012_40_configurationIsUsed() {
		FakeTransport transport = new FakeTransport(false);
		OpenAiRealtimeCalls port = new OpenAiRealtimeCalls(settings("http://otro.proveedor.test/v9", "/llamadas",
				"/llamadas/{callId}/colgar", "modelo-x", "voz-y"), transport, JSON);

		RealtimeCallPort.CreatedCall call = port.createCall(new RealtimeCallPort.CallRequest(OFFER, "x"));
		port.hangup(call.providerCallId());

		assertThat(transport.urls).containsExactly("http://otro.proveedor.test/v9/llamadas",
				"http://otro.proveedor.test/v9/llamadas/rtc_abc123/colgar");
		JsonNode session = JSON.readTree(transport.bodies.get(0).get("session"));
		assertThat(session.path("model").asString()).isEqualTo("modelo-x");
		assertThat(session.at("/audio/output/voice").asString()).isEqualTo("voz-y");
		assertThat(port.info()).isEqualTo(new RealtimeCallPort.Info("realtime/openai", "modelo-x", "voz-y",
				"gpt-4o-mini-transcribe"));
		assertThat(port.events().channel()).isEqualTo("oai-events");
	}

	@Test
	@DisplayName("012-42: un 4xx/5xx o una respuesta que no es SDP es una falla, sin el cuerpo del proveedor")
	void t012_42_badResponses() {
		RealtimeTransport notSdp = new FakeTransport(false) {
			@Override
			public Response postMultipart(String url, Map<String, String> fields, Duration timeout) {
				return new Response(200, "{\"error\":\"no\"}", null);
			}
		};
		OpenAiRealtimeCalls port = new OpenAiRealtimeCalls(settings("https://api.openai.com/v1", "/realtime/calls",
				"/realtime/calls/{callId}/hangup", "m", "v"), notSdp, JSON);

		assertThatThrownBy(() -> port.createCall(new RealtimeCallPort.CallRequest(OFFER, "x")))
				.isInstanceOf(AiCallException.class);
		assertThatThrownBy(() -> port(true).createCall(new RealtimeCallPort.CallRequest(OFFER, "secreto-guion")))
				.isInstanceOf(AiCallException.class).hasMessageContaining("500").message()
				.doesNotContain("secreto-guion");
	}

	@Test
	@DisplayName("012-39: el id de la llamada se toma del último tramo de Location; uno raro se ignora")
	void t012_39_callIdFromLocation() {
		assertThat(OpenAiRealtimeCalls.callId("/v1/realtime/calls/rtc_123")).isEqualTo("rtc_123");
		assertThat(OpenAiRealtimeCalls.callId("https://api.openai.com/v1/realtime/calls/rtc_9/?x=1"))
				.isEqualTo("rtc_9");
		assertThat(OpenAiRealtimeCalls.callId("/calls/<script>")).isNull();
		assertThat(OpenAiRealtimeCalls.callId(null)).isNull();
	}

}
