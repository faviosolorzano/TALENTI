package com.talenti.practice.adapter;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.talenti.practice.port.AiCallException;
import com.talenti.practice.port.SpeechToTextPort;

/**
 * Voz a texto SIMULADA (principio 8): sin red. Si el audio es un WAV con fragmento {@code TXT }, "escucha" ese texto;
 * si es silencio, devuelve vacío ({@code no-speech}, 012-08); si no, una frase fija. Demora y fallas programables
 * (también "falla solo la primera vez", para probar el reintento de 012-11). No guarda el audio: solo recuerda su
 * tamaño.
 */
public class SimulatedSpeechToText implements SpeechToTextPort {

	public static final String DEFAULT_TEXT = "Esta es una respuesta simulada por voz: usé Java y Spring Boot en un "
			+ "proyecto de tareas y aprendí a organizar el código en capas.";

	private final Simulation simulation = new Simulation();

	private volatile Simulation.Mode mode = Simulation.Mode.NORMAL;

	private volatile Duration delay = Duration.ZERO;

	private final AtomicInteger failuresLeft = new AtomicInteger();

	public SimulatedSpeechToText mode(Simulation.Mode mode) {
		this.mode = mode;
		return this;
	}

	public SimulatedSpeechToText delay(Duration delay) {
		this.delay = delay;
		return this;
	}

	/** Las próximas {@code times} llamadas fallan enseguida; después responde normal. */
	public SimulatedSpeechToText failNext(int times) {
		failuresLeft.set(times);
		return this;
	}

	public void reset() {
		mode = Simulation.Mode.NORMAL;
		delay = Duration.ZERO;
		failuresLeft.set(0);
		simulation.clearJournal();
	}

	/** Solo tamaños y tipos: el simulado tampoco recuerda el contenido del audio. */
	public List<String> journal() {
		return simulation.journal();
	}

	@Override
	public String adapter() {
		return "simulated";
	}

	@Override
	public String model() {
		return "stt-simulado";
	}

	@Override
	public Transcription transcribe(byte[] audio, String mime) {
		simulation.remember("STT mime=" + mime + " bytes=" + (audio == null ? 0 : audio.length));
		if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
			throw new AiCallException("Falla simulada de voz a texto");
		}
		Simulation.delay(delay);
		if (mode == Simulation.Mode.FAIL) {
			throw new AiCallException("Falla simulada de voz a texto");
		}
		if (mode == Simulation.Mode.HANG) {
			Simulation.hang();
		}
		if (Wav.isWav(audio)) {
			String spoken = Wav.spokenText(audio);
			if (spoken != null) {
				return new Transcription(spoken, null);
			}
			if (Wav.silent(audio)) {
				return new Transcription("", null);
			}
		}
		return new Transcription(DEFAULT_TEXT, null);
	}

	@Override
	public String toString() {
		return "SimulatedSpeechToText";
	}

}
