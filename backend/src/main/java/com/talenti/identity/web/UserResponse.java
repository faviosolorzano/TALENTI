package com.talenti.identity.web;

import java.util.UUID;

import com.talenti.identity.CurrentUser;
import com.talenti.identity.Role;

/** Respuesta de registro e ingreso: {@code {id, email, role}}. */
record UserResponse(UUID id, String email, Role role) {

	static UserResponse of(CurrentUser user) {
		return new UserResponse(user.id(), user.email(), user.role());
	}

	/** Sin el correo: los registros del framework imprimen las respuestas con este método. */
	@Override
	public String toString() {
		return "UserResponse[id=" + id + ", role=" + role + "]";
	}

}
