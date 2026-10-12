package com.talenti.identity;

/** Rol de un usuario (RF-06). En el E2 solo {@code STUDENT} tiene pantallas. */
public enum Role {

	STUDENT,
	COMPANY,
	ADMIN;

	/** Nombre de la autoridad en Spring Security, por ejemplo {@code ROLE_STUDENT}. */
	public String authority() {
		return "ROLE_" + name();
	}

}
