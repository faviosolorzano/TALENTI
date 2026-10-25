package com.talenti.practice.web;

import java.util.UUID;

import com.talenti.practice.service.DoraErrors;
import com.talenti.shared.web.ApiException;

/** Ids de la ruta: uno mal formado es igual que uno inexistente (404, 012-32). */
final class Ids {

	private Ids() {
	}

	static UUID session(String raw) {
		return parse(raw, DoraErrors.sessionNotFound());
	}

	static UUID parse(String raw, ApiException notFound) {
		try {
			return UUID.fromString(raw);
		}
		catch (IllegalArgumentException | NullPointerException malformed) {
			throw notFound;
		}
	}

}
