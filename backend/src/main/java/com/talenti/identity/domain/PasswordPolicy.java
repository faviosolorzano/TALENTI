package com.talenti.identity.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.talenti.shared.web.FieldViolation;

/**
 * Política de contraseña (RNF-01, spec 001 sección 10): largo mínimo y máximo configurables, al menos una
 * letra y un número, y distinta del correo. La contraseña no se recorta. Además, BCrypt solo usa los primeros
 * 72 bytes, así que se rechaza lo que los exceda en vez de ignorarlo en silencio.
 */
public record PasswordPolicy(int minLength, int maxLength) {

	/** Límite técnico de BCrypt, en bytes UTF-8. */
	public static final int BCRYPT_MAX_BYTES = 72;

	public PasswordPolicy {
		if (minLength < 1 || maxLength < minLength) {
			throw new IllegalArgumentException("Largo de contraseña mal configurado");
		}
	}

	/** Reglas incumplidas (vacío si cumple). {@code normalizedEmail} es el correo ya recortado y en minúsculas. */
	public List<FieldViolation> check(String password, String normalizedEmail) {
		List<FieldViolation> violations = new ArrayList<>();
		if (password == null || password.isEmpty()) {
			violations.add(FieldViolation.of("password", "required"));
			return violations;
		}
		int length = password.codePointCount(0, password.length());
		if (length < minLength) {
			violations.add(FieldViolation.of("password", "min-length", minLength));
		}
		if (length > maxLength) {
			violations.add(FieldViolation.of("password", "max-length", maxLength));
		}
		else if (exceedsBcryptLimit(password)) {
			violations.add(FieldViolation.of("password", "max-bytes", BCRYPT_MAX_BYTES));
		}
		if (password.codePoints().noneMatch(Character::isLetter)) {
			violations.add(FieldViolation.of("password", "needs-letter"));
		}
		if (password.codePoints().noneMatch(Character::isDigit)) {
			violations.add(FieldViolation.of("password", "needs-digit"));
		}
		if (!normalizedEmail.isEmpty() && password.toLowerCase(Locale.ROOT).equals(normalizedEmail)) {
			violations.add(FieldViolation.of("password", "same-as-email"));
		}
		return violations;
	}

	public static boolean exceedsBcryptLimit(String password) {
		return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
	}

}
