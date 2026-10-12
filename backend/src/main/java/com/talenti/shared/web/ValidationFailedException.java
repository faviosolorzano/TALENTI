package com.talenti.shared.web;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** 422: una o más reglas de validación incumplidas; informa el campo y la regla (001-05). */
public class ValidationFailedException extends ApiException {

	public static final String CODE = "validation-failed";

	public ValidationFailedException(List<FieldViolation> violations) {
		super(HttpStatus.UNPROCESSABLE_ENTITY, CODE, violations, new HttpHeaders());
	}

}
