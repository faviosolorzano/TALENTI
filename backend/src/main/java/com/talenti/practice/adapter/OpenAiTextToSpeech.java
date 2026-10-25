package com.talenti.practice.adapter;

import java.io.IOException;
import java.time.Duration;

import com.talenti.practice.port.AiCallException;
import com.talenti.practice.port.SpokenAudio;
import com.talenti.practice.port.TextToSpeechPort;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Texto a voz con OpenAI ({@code /audio/speech}, MP3) con la voz femenina de DORA elegida por configuración (D-14).
 * APAGADO por defecto. La duración se estima por el largo del texto (unos 65 ms por carácter en español).
 */
public class OpenAiTextToSpeech implements TextToSpeechPort {

	private final String model;

	private final String voice;

	private final OpenAiTransport transport;

	private final JsonMapper mapper;

	private final Duration timeout;

	public OpenAiTextToSpeech(String model, String voice, OpenAiTransport transport, JsonMapper mapper,
			Duration timeout) {
		if (model == null || model.isBlank() || voice == null || voice.isBlank()) {
			throw new IllegalStateException("DORA con OpenAI está encendida pero falta talenti.ai.dora.models.tts o "
					+ "talenti.ai.dora.voice (TALENTI_AI_DORA_MODELS_TTS, TALENTI_AI_DORA_VOICE)");
		}
		this.model = model;
		this.voice = voice;
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
	public String voice() {
		return voice;
	}

	@Override
	public SpokenAudio synthesize(String text) {
		ObjectNode body = mapper.createObjectNode();
		body.put("model", model);
		body.put("voice", voice);
		body.put("input", text);
		body.put("response_format", "mp3");
		byte[] audio;
		try {
			audio = transport.postJsonForBytes("/audio/speech", mapper.writeValueAsString(body), timeout);
		}
		catch (IOException e) {
			throw new AiCallException("Falló la llamada al servicio de voz");
		}
		if (audio == null || audio.length == 0) {
			throw new AiCallException("El servicio de voz no devolvió audio");
		}
		return new SpokenAudio("audio/mpeg", audio, text.length() * 65);
	}

	@Override
	public String toString() {
		return "OpenAiTextToSpeech[model=" + model + "]";
	}

}
