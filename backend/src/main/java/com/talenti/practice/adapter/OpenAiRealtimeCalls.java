package com.talenti.practice.adapter;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.talenti.practice.port.AiCallException;
import com.talenti.practice.port.RealtimeCallPort;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adaptador de OpenAI en tiempo real por la interfaz unificada mediada por el servidor (spec 012, enmienda 2): reenvía
 * la oferta SDP sin cambios con la configuración de la sesión ({@code session} con {@code type: "realtime"}, modelo,
 * instrucciones, transcripción, detección de turnos con interrupción, voz y las herramientas {@code set_stage} y
 * {@code end_conversation}) y devuelve la respuesta SDP. El id de la llamada sale de la cabecera {@code Location} (a
 * confirmar en 012-69). URL, rutas, modelo, voz y nombres son configuración.
 *
 * <p>Nunca registra el SDP, las instrucciones ni la clave; los errores llevan solo el estado HTTP (012-57).
 */
public class OpenAiRealtimeCalls implements RealtimeCallPort {

	/** Configuración del adaptador (sale de {@code talenti.ai.dora.realtime.*}). */
	public record Settings(String baseUrl, String callsPath, String hangupPath, String model, String voice,
			String transcriptionModel, String transcriptionLanguage, String turnDetection, Duration timeout,
			EventNames events) {
	}

	private final Settings settings;

	private final RealtimeTransport transport;

	private final JsonMapper mapper;

	public OpenAiRealtimeCalls(Settings settings, RealtimeTransport transport, JsonMapper mapper) {
		this.settings = settings;
		this.transport = transport;
		this.mapper = mapper;
	}

	@Override
	public Info info() {
		return new Info("realtime/openai", settings.model(), settings.voice(), settings.transcriptionModel());
	}

	@Override
	public EventNames events() {
		return settings.events();
	}

	@Override
	public CreatedCall createCall(CallRequest request) {
		Map<String, String> fields = new LinkedHashMap<>();
		fields.put("sdp", request.sdp());
		fields.put("session", session(request.instructions()));
		RealtimeTransport.Response response;
		try {
			response = transport.postMultipart(settings.baseUrl() + settings.callsPath(), fields, settings.timeout());
		}
		catch (IOException | RuntimeException e) {
			throw new AiCallException("No se pudo abrir la llamada en tiempo real");
		}
		if (response.status() / 100 != 2) {
			throw new AiCallException("El servicio en tiempo real respondió con el estado " + response.status());
		}
		String answer = response.body() == null ? "" : response.body();
		if (!answer.strip().startsWith("v=0")) {
			throw new AiCallException("El servicio en tiempo real no devolvió una respuesta SDP");
		}
		return new CreatedCall(callId(response.location()), answer);
	}

	@Override
	public void hangup(String providerCallId) {
		if (providerCallId == null || providerCallId.isBlank()) {
			return;
		}
		int status;
		try {
			status = transport.post(settings.baseUrl() + settings.hangupPath().replace("{callId}", providerCallId),
					settings.timeout());
		}
		catch (IOException | RuntimeException e) {
			throw new AiCallException("No se pudo colgar la llamada en tiempo real");
		}
		if (status / 100 != 2 && status != 404) {
			throw new AiCallException("Colgar respondió con el estado " + status);
		}
	}

	/** La configuración de la sesión, en JSON (forma a confirmar en 012-69). */
	String session(String instructions) {
		ObjectNode session = mapper.createObjectNode();
		session.put("type", "realtime");
		session.put("model", settings.model());
		session.put("instructions", instructions);
		ObjectNode audio = session.putObject("audio");
		ObjectNode input = audio.putObject("input");
		ObjectNode transcription = input.putObject("transcription").put("model", settings.transcriptionModel());
		if (settings.transcriptionLanguage() != null && !settings.transcriptionLanguage().isBlank()) {
			transcription.put("language", settings.transcriptionLanguage());
		}
		input.putObject("turn_detection").put("type", settings.turnDetection()).put("interrupt_response", true)
				.put("create_response", true);
		audio.putObject("output").put("voice", settings.voice());
		ArrayNode tools = session.putArray("tools");
		ObjectNode stage = tools.addObject().put("type", "function").put("name", "set_stage").put("description",
				"Informa la etapa de la conversación cada vez que cambia: GREETING, INTRO, QUESTION (con position), "
						+ "FOLLOW_UP (con position), CANDIDATE_QUESTIONS o CLOSING.");
		ObjectNode parameters = stage.putObject("parameters").put("type", "object");
		ObjectNode properties = parameters.putObject("properties");
		ArrayNode phases = properties.putObject("phase").put("type", "string").putArray("enum");
		for (String phase : new String[] { "GREETING", "INTRO", "QUESTION", "FOLLOW_UP", "CANDIDATE_QUESTIONS",
				"CLOSING" }) {
			phases.add(phase);
		}
		properties.putObject("position").put("type", "integer").put("description",
				"Número de la pregunta del plan (solo en QUESTION y FOLLOW_UP).");
		parameters.putArray("required").add("phase");
		tools.addObject().put("type", "function").put("name", "end_conversation")
				.put("description", "Termina la conversación después de la despedida.").putObject("parameters")
				.put("type", "object").putObject("properties");
		session.put("tool_choice", "auto");
		return mapper.writeValueAsString(session);
	}

	/** El último tramo de {@code Location} (por ejemplo {@code /v1/realtime/calls/rtc_123} → {@code rtc_123}). */
	static String callId(String location) {
		if (location == null || location.isBlank()) {
			return null;
		}
		String value = location.strip();
		int query = value.indexOf('?');
		if (query >= 0) {
			value = value.substring(0, query);
		}
		while (value.endsWith("/")) {
			value = value.substring(0, value.length() - 1);
		}
		String id = value.substring(value.lastIndexOf('/') + 1);
		return id.isBlank() || id.length() > 120 || !id.matches("[A-Za-z0-9_\\-]+") ? null : id;
	}

}
