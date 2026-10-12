package com.talenti.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** 001-17: detección de marcadores sin completar en los textos legales (aviso al arrancar). */
class LegalPlaceholdersTests {

	@Test
	@DisplayName("001-17: encuentra los marcadores [...] distintos, en orden, sin repetirlos")
	void t001_17_findsDistinctPlaceholdersInOrder() {
		String markdown = """
				Responsable: [titular del servicio], con [RUC].
				Escribe a [correo de contacto]. Otra vez [titular del servicio].
				[Si corresponde: Libro de Reclamaciones virtual en este enlace.]
				""";

		assertThat(LegalPlaceholders.find(markdown)).containsExactly("[titular del servicio]", "[RUC]",
				"[correo de contacto]", "[Si corresponde: Libro de Reclamaciones virtual en este enlace.]");
	}

	@Test
	@DisplayName("001-17: no cuenta los enlaces de Markdown ni los textos sin corchetes")
	void t001_17_ignoresLinksAndPlainText() {
		assertThat(LegalPlaceholders.find("Lee la [Política de Cookies](/cookies) y nada más.")).isEmpty();
		assertThat(LegalPlaceholders.find("Texto completo sin marcadores.")).isEmpty();
		assertThat(LegalPlaceholders.find("")).isEmpty();
		assertThat(LegalPlaceholders.find("Un [ ] vacío y un [] más")).containsExactly("[ ]");
	}

	@Test
	@DisplayName("001-17: los borradores v1 actuales sí tienen marcadores pendientes (por eso el aviso al arrancar)")
	void t001_17_currentDraftsHavePendingPlaceholders() throws IOException {
		String terms = new String(new ClassPathResource("legal/v1/terms.es.md").getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);
		String privacy = new String(new ClassPathResource("legal/v1/privacy.es.md").getInputStream().readAllBytes(),
				StandardCharsets.UTF_8);

		assertThat(LegalPlaceholders.find(terms)).contains("[titular del servicio]", "[correo de contacto]");
		// El enlace [Política de Cookies](/cookies) de la política de privacidad no es un marcador.
		assertThat(LegalPlaceholders.find(privacy)).doesNotContain("[Política de Cookies]");
	}

}
