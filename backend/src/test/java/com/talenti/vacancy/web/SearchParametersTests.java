package com.talenti.vacancy.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import com.talenti.shared.web.FieldViolation;
import com.talenti.shared.web.ValidationFailedException;
import com.talenti.vacancy.domain.Modality;
import com.talenti.vacancy.domain.SearchSort;

/** Validación de los parámetros del listado (004-10) y de las destacadas (004-12): sin base de datos. */
class SearchParametersTests {

	private static MultiValueMap<String, String> query(String... pairs) {
		LinkedMultiValueMap<String, String> map = new LinkedMultiValueMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.add(pairs[i], pairs[i + 1]);
		}
		return map;
	}

	private static List<FieldViolation> violationsOf(MultiValueMap<String, String> query) {
		return ((ValidationFailedException) catchThrowable(query)).getViolations();
	}

	private static Throwable catchThrowable(MultiValueMap<String, String> query) {
		try {
			SearchParameters.parse(query);
		}
		catch (ValidationFailedException e) {
			return e;
		}
		throw new AssertionError("Se esperaba un 422");
	}

	@Test
	@DisplayName("004-08: sin parámetros: página 1, tamaño 9, orden recent y sin filtros")
	void t004_08_defaults() {
		SearchParameters parameters = SearchParameters.parse(query());

		assertThat(parameters.page()).isEqualTo(1);
		assertThat(parameters.size()).isEqualTo(9);
		assertThat(parameters.criteria().sort()).isEqualTo(SearchSort.RECENT);
		assertThat(parameters.criteria().terms()).isEmpty();
		assertThat(parameters.criteria().modalities()).isEmpty();
		assertThat(parameters.criteria().locations()).isEmpty();
		assertThat(parameters.criteria().skills()).isEmpty();
	}

	@Test
	@DisplayName("004-06: q se limpia y se parte en términos sin repetir")
	void t004_06_queryBecomesTerms() {
		SearchParameters parameters = SearchParameters.parse(query("q", "  Backend   JAVA  backend "));

		assertThat(parameters.criteria().terms()).containsExactly("Backend", "JAVA");
		assertThat(SearchParameters.parse(query("q", "   ")).criteria().terms()).isEmpty();
	}

	@Test
	@DisplayName("004-07: los filtros repetibles se deduplican y conservan su orden")
	void t004_07_repeatableFiltersAreDeduplicated() {
		SearchParameters parameters = SearchParameters.parse(query("modality", "REMOTE", "modality", "HYBRID",
				"modality", "REMOTE", "location", "Lima", "location", "lima", "location", "Arequipa", "skill", "java",
				"skill", "java", "skill", "git"));

		assertThat(parameters.criteria().modalities()).containsExactly(Modality.REMOTE, Modality.HYBRID);
		assertThat(parameters.criteria().locations()).containsExactly("Lima", "Arequipa");
		assertThat(parameters.criteria().skills()).containsExactly("java", "git");
	}

	@Test
	@DisplayName("004-10: modality, sort y skill mal formados o desconocidos dan 422")
	void t004_10_invalidFilters() {
		assertThat(violationsOf(query("modality", "FOO"))).containsExactly(FieldViolation.of("modality", "invalid"));
		assertThat(violationsOf(query("modality", "remote"))).containsExactly(FieldViolation.of("modality", "invalid"));
		assertThat(violationsOf(query("sort", "oldest"))).containsExactly(FieldViolation.of("sort", "invalid"));
		assertThat(violationsOf(query("skill", "Java"))).containsExactly(FieldViolation.of("skill", "invalid"));
		assertThat(violationsOf(query("skill", "a b"))).containsExactly(FieldViolation.of("skill", "invalid"));
		assertThat(violationsOf(query("skill", "a".repeat(41)))).containsExactly(FieldViolation.of("skill", "invalid"));
	}

	@Test
	@DisplayName("004-10: page menor que 1 o no numérica y size fuera de 1 a 24 dan 422")
	void t004_10_invalidPagination() {
		assertThat(violationsOf(query("page", "0"))).containsExactly(FieldViolation.of("page", "out-of-range", 1,
				Integer.MAX_VALUE));
		assertThat(violationsOf(query("page", "x"))).containsExactly(FieldViolation.of("page", "invalid"));
		assertThat(violationsOf(query("size", "0"))).containsExactly(FieldViolation.of("size", "out-of-range", 1, 24));
		assertThat(violationsOf(query("size", "25"))).containsExactly(FieldViolation.of("size", "out-of-range", 1, 24));
		assertThat(SearchParameters.parse(query("size", "24")).size()).isEqualTo(24);
		assertThat(SearchParameters.parse(query("size", "1")).size()).isEqualTo(1);
		assertThat(SearchParameters.parse(query("page", " 3 ")).page()).isEqualTo(3);
	}

	@Test
	@DisplayName("004-10: q de más de 80 caracteres da 422; con 80 pasa; los espacios sobrantes no cuentan")
	void t004_10_queryLength() {
		assertThat(violationsOf(query("q", "a".repeat(81)))).containsExactly(FieldViolation.of("q", "too-long", 80));
		assertThat(SearchParameters.parse(query("q", "a".repeat(80))).criteria().terms()).hasSize(1);
		assertThat(SearchParameters.parse(query("q", " ".repeat(50) + "ab" + " ".repeat(50))).criteria().terms())
				.containsExactly("ab");
		// Más de 80 caracteres con espacios internos (que no se colapsan) también es 422.
		String spaced = "programacion backend fullstack ".repeat(3).strip();
		assertThat(spaced.length()).isGreaterThan(80);
		assertThat(violationsOf(query("q", spaced))).containsExactly(FieldViolation.of("q", "too-long", 80));
		assertThat(violationsOf(query("q", "a ".repeat(41)))).as("81 caracteres de letras sueltas y espacios")
				.containsExactly(FieldViolation.of("q", "too-long", 80));
		// Cuenta caracteres, no bytes: 80 letras con tilde (2 bytes cada una) son válidas.
		assertThat(SearchParameters.parse(query("q", "ñ".repeat(80))).criteria().terms()).hasSize(1);
	}

	@Test
	@DisplayName("004-10: máximo 6 términos (422 too-many-terms); 6 pasan, los repetidos y los de una letra no cuentan")
	void t004_10_maxSixTerms() {
		assertThat(violationsOf(query("q", "uno dos tres cuatro cinco seis siete")))
				.containsExactly(FieldViolation.of("q", "too-many-terms", 6));
		assertThat(SearchParameters.parse(query("q", "uno dos tres cuatro cinco seis")).criteria().terms()).hasSize(6);
		assertThat(SearchParameters.parse(query("q", "uno DOS tres cuatro cinco seis Uno a b c d e f g")).criteria()
				.terms()).as("repetidos y de una letra no cuentan").hasSize(6);
		assertThat(violationsOf(query("q", "ab cd ef gh ij kl mn"))).containsExactly(
				FieldViolation.of("q", "too-many-terms", 6));
		assertThat(SearchParameters.parse(query("q", "a b c")).criteria().terms()).as("solo letras sueltas: no filtra")
				.isEmpty();
	}

	@Test
	@DisplayName("004-10: más de 10 valores distintos en un filtro dan 422; repetidos cuentan una vez")
	void t004_10_tooManyValues() {
		MultiValueMap<String, String> eleven = new LinkedMultiValueMap<>();
		MultiValueMap<String, String> ten = new LinkedMultiValueMap<>();
		for (int i = 1; i <= 11; i++) {
			eleven.add("skill", "s" + i);
			if (i <= 10) {
				ten.add("skill", "s" + i);
			}
		}

		assertThat(violationsOf(eleven)).containsExactly(FieldViolation.of("skill", "too-many", 10));
		assertThat(SearchParameters.parse(ten).criteria().skills()).hasSize(10);
		MultiValueMap<String, String> repeated = new LinkedMultiValueMap<>();
		for (int i = 0; i < 50; i++) {
			repeated.add("skill", "git");
		}
		assertThat(SearchParameters.parse(repeated).criteria().skills()).containsExactly("git");
	}

	@Test
	@DisplayName("004-10: location con caracteres de control o demasiado larga da 422; una ciudad inexistente pasa")
	void t004_10_location() {
		assertThat(violationsOf(query("location", "Li\u0000ma"))).containsExactly(FieldViolation.of("location", "invalid"));
		assertThat(violationsOf(query("location", "x".repeat(61)))).containsExactly(FieldViolation.of("location", "invalid"));
		assertThat(SearchParameters.parse(query("location", "Atlántida")).criteria().locations())
				.containsExactly("Atlántida");
		assertThat(SearchParameters.parse(query("location", " Lima ")).criteria().locations()).containsExactly("Lima");
	}

	@Test
	@DisplayName("004-10: un parámetro vacío cuenta como ausente")
	void t004_10_emptyMeansAbsent() {
		SearchParameters parameters = SearchParameters.parse(query("q", "", "modality", "", "location", "", "skill", "",
				"sort", "", "page", "", "size", ""));

		assertThat(parameters.page()).isEqualTo(1);
		assertThat(parameters.size()).isEqualTo(9);
		assertThat(parameters.criteria().modalities()).isEmpty();
	}

	@Test
	@DisplayName("004-10: los errores no repiten el valor recibido")
	void t004_10_errorsDoNotEchoInput() {
		Throwable error = catchThrowable(query("q", "z".repeat(90), "modality", "secreto-123"));

		assertThat(error.toString()).doesNotContain("zzzz").doesNotContain("secreto-123");
		assertThat(((ValidationFailedException) error).getViolations().toString()).doesNotContain("secreto-123");
	}

	@Test
	@DisplayName("004-12: limit por defecto 4, máximo 8; fuera de 1 a 8 o no numérico da 422")
	void t004_12_limit() {
		assertThat(SearchParameters.limit(query(), 4, 8)).isEqualTo(4);
		assertThat(SearchParameters.limit(query("limit", "8"), 4, 8)).isEqualTo(8);
		assertThat(SearchParameters.limit(query("limit", "1"), 4, 8)).isEqualTo(1);
		for (String bad : List.of("0", "9", "-1", "ocho", "1.5")) {
			assertThatThrownBy(() -> SearchParameters.limit(query("limit", bad), 4, 8))
					.isInstanceOf(ValidationFailedException.class);
		}
	}

}
