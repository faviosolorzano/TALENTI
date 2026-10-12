package com.talenti.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 001-05: normalización y validación del correo. */
class EmailPolicyTests {

	@Test
	@DisplayName("001-05: el correo se recorta y se pasa a minúsculas")
	void t001_05_normalizeTrimsAndLowercases() {
		assertThat(EmailPolicy.normalize("  Ana.Perez@Ejemplo.TEST \t")).isEqualTo("ana.perez@ejemplo.test");
		assertThat(EmailPolicy.normalize(null)).isEmpty();
		assertThat(EmailPolicy.normalize("   ")).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "ana@ejemplo.test", "ana.perez+practicas@ejemplo.pe", "a_b-c@sub.dominio.edu.pe",
			"x@y.co", "o'brien@ejemplo.test", "123@456.com" })
	@DisplayName("001-05: correos bien formados son válidos")
	void t001_05_validEmails(String email) {
		assertThat(EmailPolicy.check(EmailPolicy.normalize(email))).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "ana", "ana@", "@ejemplo.test", "ana@ejemplo", "ana@@ejemplo.test",
			"ana perez@ejemplo.test", "ana@ejemplo..test", ".ana@ejemplo.test", "ana.@ejemplo.test",
			"ana@-ejemplo.test", "ana@ejemplo.t", "ana@ejemplo.test.", "ana@ejemplo.123", "añ@ejemplo.test",
			"ana@ejemplo.test\nbcc:otro@ejemplo.test" })
	@DisplayName("001-05: correos mal formados se rechazan con invalid-format")
	void t001_05_invalidEmails(String email) {
		assertThat(EmailPolicy.check(EmailPolicy.normalize(email))).hasValueSatisfying(violation -> {
			assertThat(violation.field()).isEqualTo("email");
			assertThat(violation.rule()).isEqualTo("invalid-format");
		});
	}

	@Test
	@DisplayName("001-05: vacío es required; más de 254 caracteres es too-long; el local de más de 64 es inválido")
	void t001_05_lengthRules() {
		assertThat(EmailPolicy.check("")).hasValueSatisfying(v -> assertThat(v.rule()).isEqualTo("required"));
		// 64 (local) + 1 (@) + 3 x 61 (etiquetas "b" x 60 y punto) + 6 = 254
		String exactly254 = "a".repeat(64) + "@" + ("b".repeat(60) + ".").repeat(3) + "c".repeat(6);
		assertThat(exactly254).hasSize(254);
		assertThat(EmailPolicy.check(exactly254)).isEmpty();
		assertThat(EmailPolicy.check(exactly254 + "d")).hasValueSatisfying(v -> {
			assertThat(v.rule()).isEqualTo("too-long");
			assertThat(v.args()).containsExactly(254);
		});
		assertThat(EmailPolicy.check("a".repeat(65) + "@ejemplo.test"))
				.hasValueSatisfying(v -> assertThat(v.rule()).isEqualTo("invalid-format"));
	}

}
