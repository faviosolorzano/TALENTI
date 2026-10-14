package com.talenti.cv.pdf;

import org.springframework.http.HttpStatus;

import com.talenti.shared.web.ApiException;

import java.util.Map;

/**
 * 422 {@code cv-unreadable} (009-05): el archivo no se puede leer como un PDF con texto. {@code reason} dice por qué
 * ({@code NOT_A_PDF}, {@code CORRUPT}, {@code ENCRYPTED}, {@code TOO_MANY_PAGES} o {@code NO_TEXT}); nunca lleva
 * contenido del archivo.
 */
public class CvUnreadableException extends ApiException {

	public static final String CODE = "cv-unreadable";

	public enum Reason {
		NOT_A_PDF, CORRUPT, ENCRYPTED, TOO_MANY_PAGES, NO_TEXT
	}

	private final Reason reason;

	public CvUnreadableException(Reason reason) {
		super(HttpStatus.UNPROCESSABLE_ENTITY, CODE, Map.of("reason", reason.name()));
		this.reason = reason;
	}

	public Reason reason() {
		return reason;
	}

}
