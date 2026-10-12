package com.talenti.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.shared.web.FieldViolation;

/** 001-05: política de contraseña (mínimo 8 por decisión de Favio, máximo 64, letra y número, distinta del correo). */
class PasswordPolicyTests {

	private final PasswordPolicy policy = new PasswordPolicy(8, 64);

	private List<String> rules(String password, String email) {
		return policy.check(password, email).stream().map(FieldViolation::rule).toList();
	}

	@Test
	@DisplayName("001-05: una contraseña con letra, número y 8 caracteres cumple")
	void t001_05_validPassword() {
		assertThat(rules("abcdefg1", "ana@ejemplo.test")).isEmpty();
		assertThat(rules("a".repeat(63) + "1", "ana@ejemplo.test")).isEmpty();
		assertThat(rules("Contraseña-ñ1", "ana@ejemplo.test")).isEmpty();
	}

	@Test
	@DisplayName("001-05: menos de 8 caracteres incumple min-length y el mínimo es configurable")
	void t001_05_minLengthIsConfigurable() {
		assertThat(rules("abcdef1", "")).containsExactly("min-length");
		assertThat(policy.check("abcdef1", "").get(0).args()).containsExactly(8);
		assertThat(new PasswordPolicy(12, 64).check("abcdefg1", "")).extracting(FieldViolation::rule)
				.containsExactly("min-length");
		assertThat(new PasswordPolicy(6, 64).check("abcde1", "")).isEmpty();
	}

	@Test
	@DisplayName("001-05: más de 64 caracteres incumple max-length")
	void t001_05_maxLength() {
		assertThat(rules("a".repeat(64) + "1", "")).containsExactly("max-length");
	}

	@Test
	@DisplayName("001-05: BCrypt solo usa 72 bytes, así que se rechaza lo que los exceda aunque tenga menos de 64 caracteres")
	void t001_05_bcryptByteLimit() {
		String longInBytes = "ñ".repeat(40) + "1"; // 41 caracteres, 81 bytes
		assertThat(rules(longInBytes, "")).containsExactly("max-bytes");
	}

	@Test
	@DisplayName("001-05: exige al menos una letra y al menos un número")
	void t001_05_needsLetterAndDigit() {
		assertThat(rules("12345678", "")).containsExactly("needs-letter");
		assertThat(rules("abcdefgh", "")).containsExactly("needs-digit");
		assertThat(rules("!!!!!!!!", "")).containsExactlyInAnyOrder("needs-letter", "needs-digit");
	}

	@Test
	@DisplayName("001-05: no puede ser igual al correo (sin distinguir mayúsculas)")
	void t001_05_notEqualToEmail() {
		assertThat(rules("Ana1@Ejemplo.test", "ana1@ejemplo.test")).containsExactly("same-as-email");
	}

	@Test
	@DisplayName("001-05: vacía o nula es required y la contraseña no se recorta")
	void t001_05_requiredAndNotTrimmed() {
		assertThat(rules("", "")).containsExactly("required");
		assertThat(rules(null, "")).containsExactly("required");
		// Los espacios cuentan: 6 caracteres + 2 espacios = 8.
		assertThat(rules("abc123  ", "")).isEmpty();
	}

}
