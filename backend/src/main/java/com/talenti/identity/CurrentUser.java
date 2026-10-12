package com.talenti.identity;

import java.io.Serializable;
import java.util.UUID;

/**
 * El usuario autenticado, tal como queda en la sesión. Es la interfaz pública de identity para los demás
 * módulos: el id del usuario siempre sale de la sesión ({@code @AuthenticationPrincipal CurrentUser}),
 * nunca de un parámetro que mande el navegador (RNF-03, 001-16).
 */
public record CurrentUser(UUID id, String email, Role role) implements Serializable {

	private static final long serialVersionUID = 1L;

	/** El correo es un dato personal: no se imprime en registros ni en trazas. */
	@Override
	public String toString() {
		return "CurrentUser[id=" + id + ", role=" + role + "]";
	}

}
