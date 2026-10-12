package com.talenti.vacancy.service;

import org.springframework.http.HttpStatus;

import com.talenti.shared.web.ApiException;

/**
 * 503 {@code search-timeout}: la búsqueda tardó más que el tiempo máximo y se canceló para no ocupar la base de
 * datos. No lleva el texto buscado ni la causa original.
 */
public class SearchTimeoutException extends ApiException {

	public static final String CODE = "search-timeout";

	public SearchTimeoutException() {
		super(HttpStatus.SERVICE_UNAVAILABLE, CODE);
	}

}
