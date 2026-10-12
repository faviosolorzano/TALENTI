package com.talenti.identity.service;

import org.springframework.http.HttpStatus;

import com.talenti.shared.web.ApiException;

/** 409: la versión de los documentos legales que se envió ya no es la vigente (001-03). */
public class LegalVersionOutdatedException extends ApiException {

	public LegalVersionOutdatedException() {
		super(HttpStatus.CONFLICT, "legal-version-outdated");
	}

}
