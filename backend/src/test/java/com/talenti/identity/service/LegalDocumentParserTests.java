package com.talenti.identity.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.identity.service.LegalCatalog.LegalDocument;

/** 001-17 y 001-18: cómo se separan título y secciones de un documento legal. */
class LegalDocumentParserTests {

	@Test
	@DisplayName("001-17: el título sale del encabezado # y cada ## es una sección con su cuerpo en Markdown")
	void t001_17_parsesTitleAndSections() {
		String markdown = """
				# Mi documento

				Versión v1. Introducción.

				## Primera
				- punto uno
				- punto dos

				## Segunda
				| a | b |
				|---|---|
				| 1 | 2 |
				""";

		LegalDocument document = LegalDocumentService.parse("terms", markdown);

		assertThat(document.key()).isEqualTo("terms");
		assertThat(document.title()).isEqualTo("Mi documento");
		assertThat(document.sections()).extracting(LegalCatalog.LegalSection::heading)
				.containsExactly("", "Primera", "Segunda");
		assertThat(document.sections().get(0).body()).isEqualTo("Versión v1. Introducción.");
		assertThat(document.sections().get(1).body()).isEqualTo("- punto uno\n- punto dos");
		assertThat(document.sections().get(2).body()).contains("| 1 | 2 |");
	}

}
