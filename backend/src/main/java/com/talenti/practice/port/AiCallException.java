package com.talenti.practice.port;

/**
 * Falló una llamada a la IA o a la voz (red, estado HTTP, formato). El mensaje es solo el tipo de fallo: NUNCA lleva
 * contenido (ni audio, ni transcripciones, ni preguntas, ni el nombre de la persona, 012-33).
 */
public class AiCallException extends RuntimeException {

	public AiCallException(String reason) {
		super(reason);
	}

}
