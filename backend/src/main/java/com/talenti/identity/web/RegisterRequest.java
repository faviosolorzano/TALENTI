package com.talenti.identity.web;

/**
 * Cuerpo de {@code POST /auth/register}. Un campo {@code role} que llegue se ignora: el registro público
 * siempre crea un estudiante (001-07).
 */
record RegisterRequest(String email, String password, Boolean legalAccepted, String legalVersion) {

	/** Sin la contraseña: este objeto no debe filtrar secretos a un registro por accidente. */
	@Override
	public String toString() {
		return "RegisterRequest[...]";
	}

}
