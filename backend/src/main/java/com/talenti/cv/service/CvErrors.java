package com.talenti.cv.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import com.talenti.shared.web.ApiException;

/** Los errores de la API del lector de CV (spec 009, sección 6). Ninguno lleva contenido del CV. */
public final class CvErrors {

	private CvErrors() {
	}

	/** 415 {@code cv-invalid-type}: el archivo no declara ser un PDF. */
	public static ApiException invalidType() {
		return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "cv-invalid-type");
	}

	/** 413 {@code cv-too-large}: el PDF pasa del tamaño máximo. */
	public static ApiException tooLarge() {
		return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "cv-too-large");
	}

	/** 409 {@code analysis-in-progress}: la persona ya tiene un análisis abierto o una lectura en curso. */
	public static ApiException inProgress() {
		return new ApiException(HttpStatus.CONFLICT, "analysis-in-progress");
	}

	/** 404 {@code analysis-not-found}: ajeno, inexistente, mal formado o ya cerrado: no se distinguen (009-17). */
	public static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "analysis-not-found");
	}

	/** 409 {@code analysis-not-ready}: todavía se está leyendo, o falló; no hay sugerencias que confirmar. */
	public static ApiException notReady() {
		return new ApiException(HttpStatus.CONFLICT, "analysis-not-ready");
	}

	/** 429 {@code cv-limit-reached} con {@code Retry-After} (en segundos): límite de abuso (009-07). */
	public static ApiException limitReached(long retryAfterSeconds) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, retryAfterSeconds)));
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "cv-limit-reached", List.of(), headers, Map.of()) {

			private static final long serialVersionUID = 1L;

		};
	}

	/** 429 {@code ai-quota-reached} con la fecha de renovación (009-28). */
	public static ApiException aiQuotaReached(Instant resetsAt, int used, int limit) {
		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("resetsAt", resetsAt.toString());
		properties.put("used", used);
		properties.put("limit", limit);
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ai-quota-reached", properties);
	}

}
