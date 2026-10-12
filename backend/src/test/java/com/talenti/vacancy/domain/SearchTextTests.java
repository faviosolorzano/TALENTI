package com.talenti.vacancy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Normalización del texto de búsqueda (004-06): reglas puras, sin base de datos. */
class SearchTextTests {

	@Test
	@DisplayName("004-06: se recorta y se colapsan los espacios, tabuladores y saltos de línea")
	void t004_06_cleanCollapsesWhitespace() {
		assertThat(SearchText.clean("  hola   mundo \t\n x ")).isEqualTo("hola mundo x");
		assertThat(SearchText.clean("a b c")).as("espacios no ASCII").isEqualTo("a b c");
	}

	@Test
	@DisplayName("004-06: sin texto, solo espacios o nulo no queda nada que buscar")
	void t004_06_blankHasNoTerms() {
		assertThat(SearchText.terms(SearchText.clean(null))).isEmpty();
		assertThat(SearchText.terms(SearchText.clean(""))).isEmpty();
		assertThat(SearchText.terms(SearchText.clean("   \t "))).isEmpty();
	}

	@Test
	@DisplayName("004-06: los caracteres de control (incluido NUL) se vuelven espacios")
	void t004_06_controlCharactersBecomeSpaces() {
		assertThat(SearchText.clean("a\u0000b")).isEqualTo("a b");
		assertThat(SearchText.clean("\u0000\u0001")).isEmpty();
		assertThat(SearchText.clean("x\u007fy")).isEqualTo("x y");
	}

	@Test
	@DisplayName("004-06: los términos se separan por espacio y no se repiten, sin importar mayúsculas ni tildes")
	void t004_06_termsAreDeduplicated() {
		assertThat(SearchText.terms("backend JAVA backend java")).containsExactly("backend", "JAVA");
		assertThat(SearchText.terms("Programación programacion PROGRAMACIÓN")).containsExactly("Programación");
		assertThat(SearchText.terms("node.js")).containsExactly("node.js");
	}

	@Test
	@DisplayName("004-06: %, _ y la barra invertida se escapan para que valgan como texto en ILIKE")
	void t004_06_likePatternEscapesWildcards() {
		assertThat(SearchText.containsPattern("java")).isEqualTo("%java%");
		assertThat(SearchText.containsPattern("100%")).isEqualTo("%100\\%%");
		assertThat(SearchText.containsPattern("a_b")).isEqualTo("%a\\_b%");
		assertThat(SearchText.containsPattern("a\\b")).isEqualTo("%a\\\\b%");
		assertThat(SearchText.containsPattern("O'Reilly \"x\"")).as("las comillas viajan como parámetro")
				.isEqualTo("%O'Reilly \"x\"%");
	}

	@Test
	@DisplayName("004-06: TextNormalizer quita tildes y mayúsculas y colapsa espacios")
	void t004_06_normalizer() {
		assertThat(TextNormalizer.normalize("  CÓNDOR   Datos ")).isEqualTo("condor datos");
		assertThat(TextNormalizer.normalize("Programación")).isEqualTo("programacion");
		assertThat(TextNormalizer.normalize("ñandú")).isEqualTo("nandu");
		assertThat(TextNormalizer.normalize(null)).isEmpty();
		assertThat(List.of(TextNormalizer.normalize("C#"), TextNormalizer.normalize(".NET"))).containsExactly("c#",
				".net");
	}

}
