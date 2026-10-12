package com.talenti.identity.service;

import org.springframework.http.HttpStatus;

import com.talenti.shared.web.ApiException;

/**
 * 401 con un mensaje genérico idéntico para correo inexistente, contraseña errónea y cuenta bloqueada
 * (001-09, 001-10): no da pistas sobre qué cuentas existen.
 */
public class InvalidCredentialsException extends ApiException {

	public InvalidCredentialsException() {
		super(HttpStatus.UNAUTHORIZED, "invalid-credentials");
	}

}
