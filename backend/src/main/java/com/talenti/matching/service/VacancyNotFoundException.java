package com.talenti.matching.service;

import org.springframework.http.HttpStatus;

import com.talenti.shared.web.ApiException;

/**
 * 404 {@code vacancy-not-found}: la vacante no existe, está cerrada o el id no es válido; no se distinguen
 * (007-14).
 */
public class VacancyNotFoundException extends ApiException {

	public static final String CODE = "vacancy-not-found";

	public VacancyNotFoundException() {
		super(HttpStatus.NOT_FOUND, CODE);
	}

}
