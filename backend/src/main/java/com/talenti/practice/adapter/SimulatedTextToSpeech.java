package com.talenti.practice.adapter;

import java.time.Duration;
import java.util.List;

import com.talenti.practice.port.AiCallException;
import com.talenti.practice.port.SpokenAudio;
import com.talenti.practice.port.TextToSpeechPort;

/**
 * Texto a voz SIMULADO (principio 8): sin red. Devuelve un WAV corto con un tono (no es una voz) tras una demora
 * programable por llamada (012-16); puede fallar o colgarse (012-17). Recuerda los textos recibidos para que las
 * pruebas comprueben, por ejemplo, que DORA dice el primer nombre (012-24).
 */
public class SimulatedTextToSpeech implements TextToSpeechPort {

	private final Simulation simulation = new Simulation();

	private volatile Simulation.Mode mode = Simulation.Mode.NORMAL;

	private volatile Duration delay = Duration.ZERO;

	public SimulatedTextToSpeech mode(Simulation.Mode mode) {
		this.mode = mode;
		return this;
	}

	public SimulatedTextToSpeech delay(Duration delay) {
		this.delay = delay;
		return this;
	}

	public void reset() {
		mode = Simulation.Mode.NORMAL;
		delay = Duration.ZERO;
		simulation.clearJournal();
	}

	public List<String> journal() {
		return simulation.journal();
	}

	@Override
	public String adapter() {
		return "simulated";
	}

	@Override
	public String model() {
		return "tts-simulado";
	}

	@Override
	public String voice() {
		return "voz-femenina-simulada";
	}

	@Override
	public SpokenAudio synthesize(String text) {
		simulation.remember("TTS " + text);
		Simulation.delay(delay);
		if (mode == Simulation.Mode.FAIL) {
			throw new AiCallException("Falla simulada de texto a voz");
		}
		if (mode == Simulation.Mode.HANG) {
			Simulation.hang();
		}
		int audioMs = Math.max(300, (text == null ? 0 : text.length()) * 60);
		return new SpokenAudio("audio/wav", Wav.of(40, false, null, null), audioMs);
	}

	@Override
	public String toString() {
		return "SimulatedTextToSpeech";
	}

}
