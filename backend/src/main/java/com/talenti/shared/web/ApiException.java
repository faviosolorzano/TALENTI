package com.talenti.shared.web;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * Error de la API con estado HTTP y código estable. Se responde como Problem Details (RFC 9457) con
 * texto en español sacado de {@code messages.properties}. El mensaje de la excepción es solo el código:
 * nunca lleva datos de personas.
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	private final List<FieldViolation> violations;

	private final HttpHeaders headers;

	public ApiException(HttpStatus status, String code) {
		this(status, code, List.of(), new HttpHeaders());
	}

	protected ApiException(HttpStatus status, String code, List<FieldViolation> violations, HttpHeaders headers) {
		super(code);
		this.status = status;
		this.code = code;
		this.violations = List.copyOf(violations);
		this.headers = headers;
	}

	public HttpStatus getStatus() {
		return status;
	}

	/** Código estable que usa la interfaz para decidir qué mostrar (por ejemplo {@code session-expired}). */
	public String getCode() {
		return code;
	}

	public List<FieldViolation> getViolations() {
		return violations;
	}

	public HttpHeaders getHeaders() {
		return headers;
	}

}
