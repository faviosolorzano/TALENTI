package com.talenti.identity.web;

import jakarta.validation.constraints.Size;

/**
 * Cuerpo de {@code POST /auth/register}. Un campo {@code role} que llegue se ignora: el registro público
 * siempre crea un estudiante (001-07). Los topes de tamaño son generosos y se aplican antes de normalizar:
 * solo evitan procesar cuerpos desmesurados (la política real de cada campo está en el dominio).
 */
record RegisterRequest(
		@Size(max = 254) String email,
		@Size(max = 256) String password,
		Boolean legalAccepted,
		@Size(max = 20) String legalVersion) {

	/** Sin la contraseña: este objeto no debe filtrar secretos a un registro por accidente. */
	@Override
	public String toString() {
		return "RegisterRequest[...]";
	}

}
