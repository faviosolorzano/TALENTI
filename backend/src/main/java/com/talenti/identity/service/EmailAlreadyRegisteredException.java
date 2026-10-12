package com.talenti.identity.service;

import org.springframework.http.HttpStatus;

import com.talenti.shared.web.ApiException;

/** 409: el correo ya tiene cuenta. No revela ningún otro dato de esa cuenta (001-04). */
public class EmailAlreadyRegisteredException extends ApiException {

	public EmailAlreadyRegisteredException() {
		super(HttpStatus.CONFLICT, "email-already-registered");
	}

}
