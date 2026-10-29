package com.talenti.practice.adapter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;

import com.talenti.practice.port.AiCallException;
import com.talenti.practice.port.RealtimeCallPort;

/**
 * Adaptador SIMULADO de la llamada en tiempo real (012-56): sin red, determinista. Devuelve un SDP fijo, guarda EN
 * MEMORIA lo recibido (para que las pruebas comprueben qué viajó; nunca va a registros) y permite programar fallas y
 * demoras. Ninguna prueba automática sale a la red.
 */
public class SimulatedRealtimeCalls implements RealtimeCallPort {

	/** Respuesta SDP fija (inventada, sin direcciones reales). */
	public static final String ANSWER_SDP = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=talenti-simulated\r\nt=0 0\r\n"
			+ "m=audio 9 UDP/TLS/RTP/SAVPF 111\r\nc=IN IP4 0.0.0.0\r\na=sendrecv\r\n"
			+ "m=application 9 UDP/DTLS/SCTP webrtc-datachannel\r\n";

	private final EventNames events;

	private final ConcurrentLinkedDeque<CallRequest> received = new ConcurrentLinkedDeque<>();

	private final ConcurrentLinkedDeque<String> hangups = new ConcurrentLinkedDeque<>();

	private volatile Simulation.Mode createMode = Simulation.Mode.NORMAL;

	private volatile Simulation.Mode hangupMode = Simulation.Mode.NORMAL;

	private volatile Duration delay = Duration.ZERO;

	public SimulatedRealtimeCalls(EventNames events) {
		this.events = events;
	}

	@Override
	public Info info() {
		return new Info("realtime/simulated", "simulated-realtime", "simulated", "simulated-transcribe");
	}

	@Override
	public EventNames events() {
		return events;
	}

	@Override
	public CreatedCall createCall(CallRequest request) {
		received.addLast(request);
		while (received.size() > 100) {
			received.pollFirst();
		}
		Simulation.delay(delay);
		switch (createMode) {
			case FAIL -> throw new AiCallException("Falla simulada de la llamada en tiempo real");
			case HANG -> Simulation.hang();
			default -> {
			}
		}
		return new CreatedCall("rtc_sim_" + UUID.randomUUID().toString().replace("-", ""), ANSWER_SDP);
	}

	@Override
	public void hangup(String providerCallId) {
		hangups.addLast(String.valueOf(providerCallId));
		switch (hangupMode) {
			case FAIL -> throw new AiCallException("Falla simulada al colgar");
			case HANG -> Simulation.hang();
			default -> {
			}
		}
	}

	// ------------------------------------------------------------------------------------------ para las pruebas

	public SimulatedRealtimeCalls createMode(Simulation.Mode mode) {
		this.createMode = mode;
		return this;
	}

	public SimulatedRealtimeCalls hangupMode(Simulation.Mode mode) {
		this.hangupMode = mode;
		return this;
	}

	public SimulatedRealtimeCalls delay(Duration value) {
		this.delay = value;
		return this;
	}

	/** Lo recibido, del más antiguo al más nuevo. */
	public List<CallRequest> received() {
		return new ArrayList<>(received);
	}

	public List<String> hangups() {
		return new ArrayList<>(hangups);
	}

	public void reset() {
		received.clear();
		hangups.clear();
		createMode = Simulation.Mode.NORMAL;
		hangupMode = Simulation.Mode.NORMAL;
		delay = Duration.ZERO;
	}

}
