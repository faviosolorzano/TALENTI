package com.talenti.identity.domain;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import com.talenti.shared.web.FieldViolation;

/**
 * Normalización y validación del correo. Se recorta y se pasa a minúsculas antes de validar y guardar
 * (spec 001, sección 4); máximo 254 caracteres. Solo acepta correos ASCII corrientes.
 */
public final class EmailPolicy {

	public static final int MAX_LENGTH = 254;

	private static final int MAX_LOCAL_LENGTH = 64;

	private static final String LOCAL_CHARS = "[a-z0-9!#$%&'*+/=?^_`{|}~-]+";

	private static final Pattern FORMAT = Pattern.compile(
			LOCAL_CHARS + "(?:\\." + LOCAL_CHARS + ")*"
					+ "@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]");

	private EmailPolicy() {
	}

	public static String normalize(String raw) {
		return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
	}

	/** Devuelve la regla incumplida de un correo ya normalizado, o vacío si es válido. */
	public static Optional<FieldViolation> check(String normalizedEmail) {
		if (normalizedEmail.isEmpty()) {
			return Optional.of(FieldViolation.of("email", "required"));
		}
		if (normalizedEmail.length() > MAX_LENGTH) {
			return Optional.of(FieldViolation.of("email", "too-long", MAX_LENGTH));
		}
		int at = normalizedEmail.indexOf('@');
		if (at < 1 || at > MAX_LOCAL_LENGTH || !FORMAT.matcher(normalizedEmail).matches()) {
			return Optional.of(FieldViolation.of("email", "invalid-format"));
		}
		return Optional.empty();
	}

}
