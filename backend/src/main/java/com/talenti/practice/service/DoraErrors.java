package com.talenti.practice.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;

import com.talenti.shared.web.ApiException;
import com.talenti.shared.web.FieldViolation;
import com.talenti.shared.web.ValidationFailedException;

/** Errores de DORA en Problem Details (textos en messages.properties, sin contenido de la persona). */
public final class DoraErrors {

	private DoraErrors() {
	}

	/** Sesión ajena o inexistente: no se distinguen (012-32). */
	public static ApiException sessionNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "session-not-found");
	}

	public static ApiException conflict(String code) {
		return new ApiException(HttpStatus.CONFLICT, code);
	}

	public static ApiException notFound(String code) {
		return new ApiException(HttpStatus.NOT_FOUND, code);
	}

	public static ValidationFailedException invalid(String field, String rule, Object... args) {
		return new ValidationFailedException(List.of(FieldViolation.of(field, rule, args)));
	}

	/** 429 del cupo personal de práctica (012-29), con la fecha de renovación. */
	public static ApiException quotaReached(Instant resetsAt, String blockedBy) {
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "ai-quota-reached",
				Map.of("resetsAt", resetsAt.toString(), "reason", blockedBy)).withMessages("practice-quota-reached");
	}

}
