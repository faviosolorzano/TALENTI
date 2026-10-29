package com.talenti.matching.service;

import org.springframework.http.HttpStatus;

import com.talenti.shared.web.ApiException;

/** 409 {@code compatibility-insufficient}: con información insuficiente (RN-02) no hay explicación (015-03). */
public class CompatibilityInsufficientException extends ApiException {

	public static final String CODE = "compatibility-insufficient";

	public CompatibilityInsufficientException() {
		super(HttpStatus.CONFLICT, CODE);
	}

}
