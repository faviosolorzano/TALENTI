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

/**
 * 012-56 y 012-40: la MISMA suite de contrato de {@link RealtimeCallPort} contra el adaptador simulado y contra el de
 * OpenAI con un TRANSPORTE SIMULADO (sin red). Además, para OpenAI: qué viaja (multipart con el SDP sin cambios y la
 * sesión con modelo, instrucciones, transcripción, detección de turnos con interrupción, voz y herramientas) y que URL,
 * rutas, modelo, voz y nombres salen de la configuración.
 */
abstract class RealtimeCallContractTests {

	static final String OFFER = "v=0\r\no=- 1 2 IN IP4 127.0.0.1\r\ns=-\r\nt=0 0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n";

	static final RealtimeCallPort.EventNames EVENTS = new RealtimeCallPort.EventNames("oai-events", "person.done",
			"dora.done", "speech.started", "speech.stopped", "function.done", "response.done");

	/** El adaptador; {@code failing} lo programa para que falle al abrir y al colgar. */
	protected abstract RealtimeCallPort port(boolean failing);

	@Test
	@DisplayName("012-56: abrir devuelve una respuesta SDP y el id de la llamada; colgar no falla")
	void t012_56_createAndHangup() {
		RealtimeCallPort port = port(false);

		RealtimeCallPort.CreatedCall call = port.createCall(new RealtimeCallPort.CallRequest(OFFER, "Instrucciones."));
		port.hangup(call.providerCallId());

		assertThat(call.sdp()).startsWith("v=0");
		assertThat(call.providerCallId()).isNotBlank();
		assertThat(port.info().adapter()).startsWith("realtime/");
		assertThat(port.info().model()).isNotBlank();
		assertThat(port.info().voice()).isNotBlank();
		assertThat(port.events()).isEqualTo(EVENTS);
	}

	@Test
	@DisplayName("012-56: una falla al abrir o al colgar es AiCallException sin contenido (ni SDP ni instrucciones)")
	void t012_56_failuresCarryNoContent() {
		RealtimeCallPort port = port(true);
		String mark = "marca" + UUID.randomUUID().toString().replace("-", "");

		assertThatThrownBy(() -> port.createCall(new RealtimeCallPort.CallRequest(OFFER + "a=x-" + mark + "\r\n", mark)))
				.isInstanceOf(AiCallException.class).message().doesNotContain(mark);
		assertThatThrownBy(() -> port.hangup("rtc_" + mark.substring(0, 8))).isInstanceOf(AiCallException.class)
				.message().doesNotContain(mark);
		assertThat(new RealtimeCallPort.CallRequest(mark, mark).toString()).doesNotContain(mark);
		assertThat(new RealtimeCallPort.CreatedCall("rtc_1", mark).toString()).doesNotContain(mark);
	}

}
