package com.talenti.vacancy.service;

import org.springframework.http.HttpStatus;

import com.talenti.shared.web.ApiException;

/** 404 {@code vacancy-not-found}: el id no existe o no es un identificador válido (no se distinguen). */
public class VacancyNotFoundException extends ApiException {

	public static final String CODE = "vacancy-not-found";

	public VacancyNotFoundException() {
		super(HttpStatus.NOT_FOUND, CODE);
	}

}
