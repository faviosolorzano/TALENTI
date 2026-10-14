package com.talenti.cv;

/** Un adaptador no pudo leer el CV. El mensaje nunca lleva contenido del CV. */
public class CvExtractionException extends RuntimeException {

	public CvExtractionException(String message) {
		super(message);
	}

	public CvExtractionException(String message, Throwable cause) {
		super(message, cause);
	}

}
