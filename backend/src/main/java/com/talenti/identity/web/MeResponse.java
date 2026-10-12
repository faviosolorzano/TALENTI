package com.talenti.identity.web;

import java.util.UUID;

import com.talenti.identity.Role;
import com.talenti.identity.service.AuthService;

/** Respuesta de {@code GET /auth/me}: {@code {id, email, role, legalAcceptancePending}}. */
record MeResponse(UUID id, String email, Role role, boolean legalAcceptancePending) {

	static MeResponse of(AuthService.Account account) {
		return new MeResponse(account.id(), account.email(), account.role(), account.legalAcceptancePending());
	}

	/** Sin el correo: los registros del framework imprimen las respuestas con este método. */
	@Override
	public String toString() {
		return "MeResponse[id=" + id + ", role=" + role + "]";
	}

}
