package com.talenti.vacancy.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Normalización del texto de búsqueda (004-06, 004-26, 004-27): reglas puras, sin base de datos. */
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
	@DisplayName("004-10: los términos de una sola letra o cifra se ignoran; los símbolos sueltos y las palabras de 2 letras no")
	void t004_10_singleLetterTermsAreIgnored() {
		assertThat(SearchText.terms("a b c java")).containsExactly("java");
		assertThat(SearchText.terms("Ñ 5 backend")).containsExactly("backend");
		assertThat(SearchText.terms("a")).isEmpty();
		assertThat(SearchText.terms("a A á")).as("ninguna cuenta").isEmpty();
		assertThat(SearchText.terms("c# js ui")).containsExactly("c#", "js", "ui");
		assertThat(SearchText.terms("% _ \\ \"")).as("los símbolos sueltos se buscan como texto")
				.containsExactly("%", "_", "\\", "\"");
	}

	@Test
	@DisplayName("004-27: una palabra de menos de 5 letras es de palabra completa; de 5 o más, también inicio de palabra")
	void t004_27_wholeWordOrPrefix() {
		assertThat(SearchText.matchPattern("java")).isEqualTo("(?<![[:alnum:]])java(?![[:alnum:]])");
		assertThat(SearchText.matchPattern("JS")).isEqualTo("(?<![[:alnum:]])js(?![[:alnum:]])");
		assertThat(SearchText.matchPattern("react")).as("5 letras: inicio de palabra")
				.isEqualTo("(?<![[:alnum:]])react");
		assertThat(SearchText.matchPattern("Programación")).as("sin tildes y en minúsculas")
				.isEqualTo("(?<![[:alnum:]])programacion");
	}

	@Test
	@DisplayName("004-27: el borde solo se exige donde la palabra empieza o termina en letra o cifra ('c#', '100%', '%')")
	void t004_27_boundaryOnlyNextToLettersAndDigits() {
		assertThat(SearchText.matchPattern("c#")).isEqualTo("(?<![[:alnum:]])c#");
		assertThat(SearchText.matchPattern("100%")).isEqualTo("(?<![[:alnum:]])100%");
		assertThat(SearchText.matchPattern("%")).isEqualTo("%");
		assertThat(SearchText.matchPattern("_")).isEqualTo("_");
		assertThat(SearchText.matchPattern(".net")).as("empieza con punto: sin borde antes")
				.isEqualTo("\\.net(?![[:alnum:]])");
	}

	@Test
	@DisplayName("004-06: todo metacarácter de expresión regular se escapa para que valga como texto")
	void t004_06_regexMetacharactersAreEscaped() {
		assertThat(SearchText.matchPattern("node.js")).isEqualTo("(?<![[:alnum:]])node\\.js");
		assertThat(SearchText.matchPattern("c++")).isEqualTo("(?<![[:alnum:]])c\\+\\+");
		assertThat(SearchText.matchPattern("a\\b")).isEqualTo("(?<![[:alnum:]])a\\\\b(?![[:alnum:]])");
		assertThat(SearchText.matchPattern("(a|b)*[x]{2}^$?")).isEqualTo(
				"\\(a\\|b\\)\\*\\[x\\]\\{2\\}\\^\\$\\?");
		assertThat(SearchText.matchPattern("O'Reilly \"x\"")).as("las comillas viajan como parámetro")
				.isEqualTo("(?<![[:alnum:]])o'reilly \"x\"");
	}

	@Test
	@DisplayName("004-31: solo las palabras de 4 letras o más, hechas de letras y cifras, se buscan por parecido")
	void t004_31_fuzzyEligibility() {
		assertThat(SearchText.fuzzyWord("Pyton")).contains("pyton");
		assertThat(SearchText.fuzzyWord("REAC")).as("4 letras").contains("reac");
		assertThat(SearchText.fuzzyWord("Programación")).as("sin tildes").contains("programacion");
		assertThat(SearchText.fuzzyWord("sql")).as("3 letras").isEmpty();
		assertThat(SearchText.fuzzyWord("js")).isEmpty();
		assertThat(SearchText.fuzzyWord("c#")).as("con símbolo: solo exacta").isEmpty();
		assertThat(SearchText.fuzzyWord("node.js")).isEmpty();
		assertThat(SearchText.fuzzyWord("100%")).isEmpty();
		assertThat(SearchText.fuzzyWord("java17")).contains("java17");
	}

	@Test
	@DisplayName("004-26: las palabras de relleno se descartan, sin importar mayúsculas ni tildes")
	void t004_26_stopWordsAreDiscarded() {
		assertThat(SearchText.terms("se usar java")).containsExactly("java");
		assertThat(SearchText.terms("Sé USAR Java")).containsExactly("Java");
		assertThat(SearchText.terms("quiero un practicante de datos con SQL")).containsExactly("datos", "SQL");
		assertThat(SearchText.terms("Práctica pasantía vacantes")).as("solo relleno: no queda nada").isEmpty();
		assertThat(SearchText.terms("practicante")).isEmpty();
		assertThat(SearchText.terms("se usar")).isEmpty();
	}

	@Test
	@DisplayName("004-26: el relleno vive en un solo lugar y no incluye tecnologías")
	void t004_26_stopWordList() {
		for (String word : List.of("se", "sé", "usar", "uso", "manejo", "conozco", "quiero", "busco", "con", "de", "en",
				"para", "practicante", "pasantía", "intern", "vacantes")) {
			assertThat(SearchStopWords.isStopWord(word)).as(word).isTrue();
		}
		for (String word : List.of("java", "js", "sql", "react", "backend", "datos", "web", "programación")) {
			assertThat(SearchStopWords.isStopWord(word)).as(word).isFalse();
		}
	}

	@Test
	@DisplayName("004-26: la puntuación que rodea a una palabra se quita; la de dentro y los símbolos de tecnologías no")
	void t004_26_edgePunctuation() {
		assertThat(SearchText.terms("java, react; ¿node.js? (sql)")).containsExactly("java", "react", "node.js", "sql");
		assertThat(SearchText.terms("Sé java.")).containsExactly("java");
		assertThat(SearchText.terms(".NET c# c++")).containsExactly(".NET", "c#", "c++");
		assertThat(SearchText.terms(", ; . ...")).isEmpty();
		assertThat(SearchText.terms("\u0301")).as("una marca de acento suelta no es nada que buscar").isEmpty();
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
