package com.talenti.matching.port;

/**
 * Falló la llamada a la IA de la explicación. El mensaje es solo el tipo de fallo: NUNCA lleva contenido (ni lo
 * enviado ni lo recibido).
 */
public class ExplainerException extends RuntimeException {

	/** {@code CALL}: red, estado HTTP o corte; {@code MALFORMED}: llegó algo, pero no es JSON del esquema pedido. */
	public enum Kind {

		CALL, MALFORMED

	}

	private final Kind kind;

	private final int outputChars;

	public ExplainerException(Kind kind, String reason, int outputChars) {
		super(reason);
		this.kind = kind;
		this.outputChars = Math.max(0, outputChars);
	}

	public Kind kind() {
		return kind;
	}

	public int outputChars() {
		return outputChars;
	}

}
