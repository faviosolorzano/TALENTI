package com.talenti.cv.service;

/** La lectura básica falló: {@code TIMEOUT} o {@code EXTRACTION_ERROR} (009-08). Sin contenido en el mensaje. */
public class ReadFailedException extends RuntimeException {

	private final String reason;

	public ReadFailedException(String reason) {
		super(reason);
		this.reason = reason;
	}

	public String reason() {
		return reason;
	}

}
