package com.talenti.practice.adapter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import com.talenti.practice.port.AiCallException;
import com.talenti.practice.port.SpeechToTextPort;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Voz a texto con OpenAI ({@code /audio/transcriptions}). APAGADO por defecto. El audio viaja solo en memoria y no se
 * guarda (D-13); la retención del proveedor se revisa antes de usarlo con personas reales (duda 14).
 */
public class OpenAiSpeechToText implements SpeechToTextPort {

	private final String model;

	private final OpenAiTransport transport;

	private final JsonMapper mapper;

	private final Duration timeout;

	public OpenAiSpeechToText(String model, OpenAiTransport transport, JsonMapper mapper, Duration timeout) {
		if (model == null || model.isBlank()) {
			throw new IllegalStateException(
					"DORA con OpenAI está encendida pero falta talenti.ai.dora.models.stt (TALENTI_AI_DORA_MODELS_STT)");
		}
		this.model = model;
		this.transport = transport;
		this.mapper = mapper;
		this.timeout = timeout;
	}

	@Override
	public String adapter() {
		return "openai";
	}

	@Override
	public String model() {
		return model;
	}

	@Override
	public Transcription transcribe(byte[] audio, String mime) {
		String extension = switch (mime == null ? "" : mime) {
			case "audio/ogg" -> "ogg";
			case "audio/mp4" -> "m4a";
			case "audio/mpeg" -> "mp3";
			case "audio/wav" -> "wav";
			default -> "webm";
		};
		String response;
		try {
			response = transport.postMultipart("/audio/transcriptions",
					Map.of("model", model, "language", "es", "response_format", "json"), "file", "turno." + extension,
					mime, audio, timeout);
		}
		catch (IOException e) {
			throw new AiCallException("Falló la llamada al servicio de voz");
		}
		try {
			JsonNode root = mapper.readTree(response);
			JsonNode text = root.path("text");
			if (!text.isString()) {
				throw new AiCallException("La respuesta del servicio de voz no tiene el formato esperado");
			}
			JsonNode duration = root.path("duration");
			return new Transcription(text.asString(), duration.isNumber() ? (int) (duration.doubleValue() * 1000) : null);
		}
		catch (JacksonException e) {
			throw new AiCallException("La respuesta del servicio de voz no se pudo leer");
		}
	}

	@Override
	public String toString() {
		return "OpenAiSpeechToText[model=" + model + "]";
	}

}
