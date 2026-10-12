package com.talenti.identity.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cuerpo de {@code POST /auth/login}. */
record LoginRequest(
		@NotBlank @Size(max = 254) String email,
		@NotBlank @Size(max = 256) String password) {

	/** Sin la contraseña: este objeto no debe filtrar secretos a un registro por accidente. */
	@Override
	public String toString() {
		return "LoginRequest[...]";
	}

}
