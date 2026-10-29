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

/** 012-56: la suite de contrato de RealtimeCallPort contra el adaptador simulado. */
class SimulatedRealtimeCallContractTests extends RealtimeCallContractTests {


	@Override
	protected RealtimeCallPort port(boolean failing) {
		SimulatedRealtimeCalls simulated = new SimulatedRealtimeCalls(EVENTS);
		if (failing) {
			simulated.createMode(Simulation.Mode.FAIL).hangupMode(Simulation.Mode.FAIL);
		}
		return simulated;
	}

	@Test
	@DisplayName("012-56: el simulado devuelve un SDP fijo y guarda lo recibido en memoria")
	void t012_56_simulatedRemembers() {
		SimulatedRealtimeCalls simulated = new SimulatedRealtimeCalls(EVENTS);

		RealtimeCallPort.CreatedCall call = simulated.createCall(new RealtimeCallPort.CallRequest(OFFER, "Hola."));
		simulated.hangup(call.providerCallId());

		assertThat(call.sdp()).isEqualTo(SimulatedRealtimeCalls.ANSWER_SDP);
		assertThat(simulated.received()).extracting(RealtimeCallPort.CallRequest::instructions).containsExactly("Hola.");
		assertThat(simulated.hangups()).containsExactly(call.providerCallId());
	}

}
