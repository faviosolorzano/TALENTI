package com.talenti.shared.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

	private final Map<String, Object> properties;

	public ApiException(HttpStatus status, String code) {
		this(status, code, List.of(), new HttpHeaders());
	}

	/** Con datos extra para el cuerpo del Problem Details (por ejemplo reason); nunca contenido de personas. */
	public ApiException(HttpStatus status, String code, Map<String, Object> properties) {
		this(status, code, List.of(), new HttpHeaders(), properties);
	}

	protected ApiException(HttpStatus status, String code, List<FieldViolation> violations, HttpHeaders headers) {
		this(status, code, violations, headers, Map.of());
	}

	protected ApiException(HttpStatus status, String code, List<FieldViolation> violations, HttpHeaders headers,
			Map<String, Object> properties) {
		super(code);
		this.status = status;
		this.code = code;
		this.violations = List.copyOf(violations);
		this.headers = headers;
		this.properties = new LinkedHashMap<>(properties);
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

	/** Propiedades adicionales del Problem Details (vacío casi siempre). */
	public Map<String, Object> getProperties() {
		return properties;
	}

}
