package com.talenti.practice.port;

/**
 * Puerto de texto a voz (spec 012, principio 8) con la voz femenina de DORA (D-14), elegida por configuración. Bloquea;
 * quien llama impone el tiempo máximo ({@code tts-timeout}) y lo interrumpe.
 */
public interface TextToSpeechPort {

	String adapter();

	String model();

	String voice();

	/** Sintetiza el texto. Falla con {@link AiCallException}. */
	SpokenAudio synthesize(String text);

}
