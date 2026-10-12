package com.talenti.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.support.ApiClient.Response;
import com.talenti.vacancy.domain.SearchText;

import tools.jackson.databind.JsonNode;

/**
 * Búsqueda más natural (enmienda 1 de la spec 004, criterios 004-26 a 004-29, 004-31 y 004-32): palabras de relleno,
 * palabra completa o inicio de palabra, al menos una palabra, orden por parecido y tolerancia a errores de tipeo.
 * Se apoya en la semilla de 12 vacantes de {@link VacancyIntegrationTest}.
 */
class VacancySearchRelevanceTests extends VacancyIntegrationTest {

	/** Vacantes de la semilla que piden JavaScript (en el título o como habilidad). */
	private static final List<String> JAVASCRIPT_TITLES = List.of("Practicante Frontend",
			"Practicante Full Stack Node.js", "Practicante PHP y Laravel");

	// ------------------------------------------------------------------------------------------ 004-26

	@Test
	@DisplayName("004-26: 'se usar java' descarta el relleno y devuelve primero 'Practicante Backend Java'")
	void t004_26_naturalPhraseFindsJava() {
		List<String> found = titles(list("?q=se%20usar%20java"));

		assertThat(found).containsExactly("Practicante Backend Java");
		assertThat(titles(list("?q=s%C3%A9%20USAR%20Java"))).as("tildes y mayúsculas").isEqualTo(found);
		assertThat(titles(list("?q=quiero%20un%20practicante%20de%20java%20con%20sql")).get(0))
				.isEqualTo("Practicante Backend Java");
	}

	@Test
	@DisplayName("004-26: 'practicante' solo, o solo palabras de relleno ('se usar'), no filtra")
	void t004_26_onlyFillerDoesNotFilter() {
		for (String query : List.of("?q=practicante", "?q=Practicante%20Practicantes", "?q=se%20usar", "?q=quiero%20de%20la",
				"?q=%C2%BFqu%C3%A9%3F")) {
			JsonNode page = list(query + "&size=24");

			assertThat(page.get("totalItems").asInt()).as(query).isEqualTo(12);
			// Sin palabras útiles el orden es el de siempre: lo más reciente primero.
			assertThat(titles(page)).as(query).containsExactlyElementsOf(SEED_TITLES_RECENT_FIRST);
		}
	}

	@Test
	@DisplayName("004-26: la puntuación alrededor de una palabra no cuenta ('java,' = 'java')")
	void t004_26_edgePunctuationIsIgnored() {
		assertThat(titles(list("?q=java%2C"))).containsExactly("Practicante Backend Java");
		assertThat(titles(list("?q=%C2%BFreact%3F")).get(0)).isEqualTo("Practicante React y TypeScript");
	}

	// ------------------------------------------------------------------------------------------ 004-27

	@Test
	@DisplayName("004-27: 'java' no devuelve vacantes que solo piden JavaScript")
	void t004_27_javaIsNotInsideJavaScript() {
		List<String> found = titles(list("?q=java&size=24"));

		assertThat(found).containsExactly("Practicante Backend Java");
		assertThat(found).doesNotContainAnyElementsOf(JAVASCRIPT_TITLES);
		// Y al revés: 'javascript' (inicio de palabra, 10 letras) encuentra JavaScript pero no Java.
		List<String> javascript = titles(list("?q=javascript&size=24"));
		assertThat(javascript).containsAll(JAVASCRIPT_TITLES).doesNotContain("Practicante Backend Java");
	}

	@Test
	@DisplayName("004-27: 'JS' encuentra las que piden JavaScript por su sinónimo, con y sin mayúsculas")
	void t004_27_jsFindsJavaScriptByAlias() {
		List<String> found = titles(list("?q=JS&size=24"));

		assertThat(found).containsAll(JAVASCRIPT_TITLES);
		assertThat(found).doesNotContain("Practicante Backend Java", "Practicante iOS");
		assertThat(titles(list("?q=js&size=24"))).isEqualTo(found);
	}

	@Test
	@DisplayName("004-27: 'programacion' y 'Programación' coinciden (inicio de palabra, sin tildes)")
	void t004_27_accentsAreIgnored() {
		List<String> withoutAccent = titles(list("?q=programacion&size=24"));

		assertThat(withoutAccent).contains("Practicante Backend Java", "Practicante iOS", "Practicante PHP y Laravel");
		assertThat(titles(list("?q=Programaci%C3%B3n&size=24"))).isEqualTo(withoutAccent);
		assertThat(titles(list("?q=PROGRAMACI%C3%93N&size=24"))).isEqualTo(withoutAccent);
	}

	@Test
	@DisplayName("004-27: una palabra de 5 letras o más también coincide como inicio de palabra; una de 4 o menos, solo completa")
	void t004_27_prefixOnlyFromFiveLetters() {
		UUID id = insertOpenVacancy("Practicante en Restaurante");
		try {
			// Puede traer también vacantes con 'REST' por parecido, pero la del inicio de palabra va primero.
			assertThat(titles(list("?q=restaur")).get(0)).as("6 letras: inicio de palabra")
					.isEqualTo("Practicante en Restaurante");
			assertThat(titles(list("?q=restaurante"))).containsExactly("Practicante en Restaurante");
			// 'rest' tiene 4 letras: encuentra REST APIs, pero no es inicio de la palabra 'restaurante'.
			assertThat(titles(list("?q=rest&size=24"))).doesNotContain("Practicante en Restaurante")
					.contains("Practicante iOS");
		}
		finally {
			deleteVacancy(id);
		}
	}

	@Test
	@DisplayName("004-27: basta una de las palabras; 'java react' devuelve las de ambas y las que cumplen las dos van primero")
	void t004_27_anyWordMatchesAndBothComeFirst() {
		OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusDays(40);
		UUID both = insertVacancy("Practicante Java y React", "Resumen de prueba.", old, "OPEN", false);
		try {
			List<String> found = titles(list("?q=java%20react&size=24"));

			assertThat(found.get(0)).as("la más antigua, pero cumple las dos").isEqualTo("Practicante Java y React");
			assertThat(found).contains("Practicante Backend Java", "Practicante React y TypeScript");
			assertThat(found.indexOf("Practicante Java y React")).isLessThan(found.indexOf("Practicante Backend Java"));
			assertThat(found).doesNotContain("Practicante Android", "Practicante iOS");
		}
		finally {
			deleteVacancy(both);
		}
	}

	@Test
	@DisplayName("004-27: los caracteres especiales no rompen la búsqueda ni actúan como comodines o expresión regular")
	void t004_27_specialCharactersNeverFail() {
		List<String> hostile = List.of("%25", "_", "%5C", "%5C%5C", "%27", "%22", "%28", "%29", "%5B", "%5D", "%2A", "%2B", "%3F",
				"%7B", "%7D", "%7C", "%5E", "%24", ".", "%2E%2A", "%28%3Fi%29java", "%5Bjava", "java%5C", "a%28b", "%5Cd%2B",
				"x%7Cjava", "%28%3F%3Cname%3Ejava%29", "%27%3B%20DROP%20TABLE%20vacancy%3B%20--", "%5B%5B%3Aalnum%3A%5D%5D",
				"O%27Reilly", "%C3%B1and%C3%BA", "c%2B%2B", "c%23", "node.js", ".net", "%F0%9F%98%80");
		for (String query : hostile) {
			Response response = client().get("/api/v1/vacancies?q=" + query);

			assertThat(response.status()).as("q=" + query).isEqualTo(200);
		}
		assertThat(count("SELECT count(*) FROM vacancy")).as("la tabla sigue ahí").isGreaterThanOrEqualTo(12);
		// '.*' y '(?i)java' no son expresiones: se buscan como texto y no encuentran nada.
		assertThat(list("?q=.*").get("totalItems").asInt()).isZero();
		assertThat(list("?q=%28%3Fi%29java").get("totalItems").asInt()).isZero();
	}

	@Test
	@DisplayName("004-27: 'c#', 'node.js' y '.net' se buscan como texto, con sus símbolos")
	void t004_27_symbolsInTechnologyNames() {
		assertThat(titles(list("?q=node.js"))).contains("Practicante Full Stack Node.js");
		assertThat(titles(list("?q=c%23"))).as("habilidad C# de la vacante .NET").contains("Practicante .NET");
		assertThat(titles(list("?q=.NET"))).contains("Practicante .NET");
		assertThat(titles(list("?q=c%23")))
				.as("'c#' no es 'c' suelto ni coincide con cualquier palabra que empiece con c")
				.doesNotContain("Practicante de Calidad");
		UUID id = insertOpenVacancy("Desarrollo en C++ para sistemas");
		try {
			assertThat(titles(list("?q=c%2B%2B"))).containsExactly("Desarrollo en C++ para sistemas");
		}
		finally {
			deleteVacancy(id);
		}
	}

	// ------------------------------------------------------------------------------------------ 004-28 / 004-29

	@Test
	@DisplayName("004-28: habilidad o título pesan más que la empresa y esta más que el resumen; 'destacada' no influye")
	void t004_28_fieldWeights() {
		OffsetDateTime old = OffsetDateTime.now(ZoneOffset.UTC).minusDays(60);
		// Misma fecha de publicación y 'destacada' al revés: solo el campo donde aparece 'zorzal' decide.
		UUID inTitle = insertVacancy("Practicante zorzal", "Resumen de prueba.", old, "OPEN", false);
		UUID inSummary = insertVacancy("Practicante uno", "Trabajo con zorzal.", old, "OPEN", true);
		UUID inDescription = insertVacancy("Practicante dos", "Resumen de prueba.", old, "OPEN", true);
		jdbc.update("UPDATE vacancy SET description = 'Aprenderás zorzal en el día a día.' WHERE id = ?", inDescription);
		try {
			List<String> found = titles(list("?q=zorzal"));

			assertThat(found).hasSize(3);
			assertThat(found.get(0)).isEqualTo("Practicante zorzal");
			assertThat(found.subList(1, 3)).containsExactlyInAnyOrder("Practicante uno", "Practicante dos");
		}
		finally {
			deleteVacancy(inTitle);
			deleteVacancy(inSummary);
			deleteVacancy(inDescription);
		}
	}

	@Test
	@DisplayName("004-29: por defecto, relevance si hay palabras útiles; sort=relevance explícito da lo mismo")
	void t004_29_relevanceIsTheDefaultWithText() {
		List<String> byDefault = titles(list("?q=react%20git&size=24"));

		assertThat(titles(list("?q=react%20git&sort=relevance&size=24"))).isEqualTo(byDefault);
		assertThat(byDefault.get(0)).as("react en el título y git como habilidad obligatoria")
				.isEqualTo("Practicante React y TypeScript");
		// recent y company se respetan aunque haya texto.
		List<String> recent = titles(list("?q=react%20git&sort=recent&size=24"));
		assertThat(recent).containsExactlyInAnyOrderElementsOf(byDefault);
		assertThat(recent).isSortedAccordingTo((a, b) -> Integer.compare(SEED_TITLES_RECENT_FIRST.indexOf(a),
				SEED_TITLES_RECENT_FIRST.indexOf(b)));
		assertThat(titles(list("?q=react%20git&sort=company&size=24"))).containsExactlyInAnyOrderElementsOf(byDefault);
	}

	@Test
	@DisplayName("004-29: sin palabras útiles, relevance (explícito o por defecto) se comporta como recent")
	void t004_29_relevanceWithoutTermsIsRecent() {
		assertThat(titles(list("?sort=relevance&size=24"))).containsExactlyElementsOf(SEED_TITLES_RECENT_FIRST);
		assertThat(titles(list("?q=&sort=relevance&size=24"))).containsExactlyElementsOf(SEED_TITLES_RECENT_FIRST);
		assertThat(titles(list("?q=se%20usar&sort=relevance&size=24"))).containsExactlyElementsOf(
				SEED_TITLES_RECENT_FIRST);
		assertThat(titles(list("?size=24"))).containsExactlyElementsOf(SEED_TITLES_RECENT_FIRST);
		assertThat(titles(list("?q=practicante&size=24"))).containsExactlyElementsOf(SEED_TITLES_RECENT_FIRST);
	}

	@Test
	@DisplayName("004-29: sort inválido responde 422 y el mensaje menciona relevance")
	void t004_29_invalidSortIs422() {
		for (String sort : List.of("oldest", "Relevance", "RELEVANCE", "relevancia", "relevance%3B")) {
			Response response = client().get("/api/v1/vacancies?q=java&sort=" + sort);

			assertThat(response.status()).as("sort=" + sort).isEqualTo(422);
			assertThat(response.json().get("errors").get(0).get("field").asString()).isEqualTo("sort");
			assertThat(response.json().get("errors").get(0).get("rule").asString()).isEqualTo("invalid");
			assertThat(response.json().get("errors").get(0).get("message").asString()).contains("relevance");
		}
	}

	@Test
	@DisplayName("004-29: el orden por parecido es estable: las páginas no repiten ni omiten vacantes")
	void t004_29_relevancePagesAreStable() {
		String query = "?q=react%20git%20sql%20docker%20programacion%20python";
		List<String> reference = ids(list(query + "&size=24"));
		assertThat(reference).hasSizeGreaterThan(8);

		for (int size : List.of(1, 3, 5)) {
			List<String> walked = new ArrayList<>();
			int totalPages = list(query + "&size=" + size).get("totalPages").asInt();
			for (int page = 1; page <= totalPages; page++) {
				walked.addAll(ids(list(query + "&size=" + size + "&page=" + page)));
			}
			assertThat(walked).as("size=%d", size).containsExactlyElementsOf(reference);
			assertThat(walked).doesNotHaveDuplicates();
		}
	}

	@Test
	@DisplayName("004-29: con la misma puntuación y fecha, el id desempata y el orden no cambia")
	void t004_29_ties() {
		OffsetDateTime same = OffsetDateTime.now(ZoneOffset.UTC).minusDays(70);
		UUID a = insertVacancy("Practicante Zafiro A", "Resumen.", same, "OPEN", false);
		UUID b = insertVacancy("Practicante Zafiro B", "Resumen.", same, "OPEN", true);
		UUID c = insertVacancy("Practicante Zafiro C", "Resumen.", same, "OPEN", false);
		try {
			List<String> all = ids(list("?q=zafiro&size=24"));
			List<String> walked = new ArrayList<>();
			for (int page = 1; page <= 3; page++) {
				walked.addAll(ids(list("?q=zafiro&size=1&page=" + page)));
			}

			assertThat(all).hasSize(3);
			assertThat(walked).containsExactlyElementsOf(all);
			assertThat(all).isEqualTo(ids(list("?q=zafiro&size=24")));
			assertThat(all).isSorted();
		}
		finally {
			deleteVacancy(a);
			deleteVacancy(b);
			deleteVacancy(c);
		}
	}

	@Test
	@DisplayName("004-29: los filtros siguen aplicando con el orden por parecido y totalItems refleja el filtro OR")
	void t004_29_filtersStillApply() {
		JsonNode page = list("?q=java%20react&modality=REMOTE");

		assertThat(titles(page)).containsExactlyInAnyOrder("Practicante Backend Java", "Practicante React y TypeScript");
		assertThat(page.get("totalItems").asInt()).isEqualTo(2);
		assertThat(list("?q=java%20react&modality=ONSITE").get("totalItems").asInt()).isZero();
	}

	@Test
	@DisplayName("004-10: más de 6 palabras útiles responde 422; el relleno no cuenta para el límite")
	void t004_10_fillerDoesNotCountTowardsTheLimit() {
		Response tooMany = client().get("/api/v1/vacancies?q=uno%20dos%20tres%20cuatro%20cinco%20seis%20siete");
		assertThat(tooMany.status()).isEqualTo(422);
		assertThat(client().get("/api/v1/vacancies?q=se%20uno%20dos%20tres%20cuatro%20cinco%20seis%20siete%20de").status())
				.isEqualTo(422);

		// 9 palabras escritas, 4 útiles: pasa.
		Response natural = client().get("/api/v1/vacancies?q=quiero%20un%20practicante%20de%20java%20con%20spring%20boot%20sql");
		assertThat(natural.status()).isEqualTo(200);
		assertThat(titles(natural.json()).get(0)).isEqualTo("Practicante Backend Java");
	}

	// ------------------------------------------------------------------------------------------ 004-31

	@Test
	@DisplayName("004-31: errores de tipeo: 'pyton' encuentra Python")
	void t004_31_pytonFindsPython() {
		assertThat(titles(list("?q=pyton&size=24"))).containsExactlyInAnyOrder("Practicante Python y automatización",
				"Practicante de Datos con SQL", "Practicante DevOps junior");
	}

	@Test
	@DisplayName("004-31: errores de tipeo: 'javva' encuentra Java y no JavaScript")
	void t004_31_javvaFindsJava() {
		List<String> found = titles(list("?q=javva&size=24"));

		assertThat(found).containsExactly("Practicante Backend Java");
	}

	@Test
	@DisplayName("004-31: errores de tipeo: 'bakend' encuentra Backend")
	void t004_31_bakendFindsBackend() {
		assertThat(titles(list("?q=bakend&size=24")).get(0)).isEqualTo("Practicante Backend Java");
	}

	@Test
	@DisplayName("004-31: una palabra de 4 letras ('reac') también se busca por parecido y encuentra React")
	void t004_31_fourLettersFindReact() {
		List<String> found = titles(list("?q=reac&size=24"));

		assertThat(found).contains("Practicante React y TypeScript", "Practicante Frontend");
		assertThat(found).doesNotContain("Practicante Backend Java");
		assertThat(titles(list("?q=Reac%2C"))).isEqualTo(found);
	}

	@Test
	@DisplayName("004-31: con tilde o mayúsculas el parecido sigue funcionando ('AUTOMATIZACIÓM')")
	void t004_31_similarityIgnoresAccentsAndCase() {
		assertThat(titles(list("?q=AUTOMATIZACI%C3%93M"))).contains("Practicante Python y automatización");
		assertThat(titles(list("?q=automatizasion"))).contains("Practicante Python y automatización");
	}

	@Test
	@DisplayName("004-31: un parecido nunca desplaza a una coincidencia exacta")
	void t004_31_similarityNeverDisplacesExact() {
		// 'pyton' escrito tal cual en un resumen (exacta, 1 punto), con la fecha más antigua, frente a las vacantes de
		// Python, que solo coinciden por parecido en el título o la habilidad (3 puntos de parecido).
		UUID exact = insertVacancy("Practicante de soporte", "Se usa pyton todos los dias.",
				OffsetDateTime.now(ZoneOffset.UTC).minusDays(90), "OPEN", false);
		try {
			List<String> found = titles(list("?q=pyton&size=24"));

			assertThat(found).hasSize(4);
			assertThat(found.get(0)).isEqualTo("Practicante de soporte");
		}
		finally {
			deleteVacancy(exact);
		}
	}

	@Test
	@DisplayName("004-31: el umbral 0,4 separa los errores de tipeo de palabras que solo se parecen por dentro")
	void t004_31_thresholdCalibration() {
		float threshold = SearchText.SIMILARITY_THRESHOLD;
		assertThat(threshold).isEqualTo(0.4f);
		// Deben pasar (errores de tipeo).
		for (String[] pair : new String[][] { { "pyton", "python" }, { "javva", "java" }, { "bakend", "backend" },
				{ "reac", "react" }, { "automatizasion", "automatizacion" }, { "laravl", "laravel" } }) {
			assertThat(similarity(pair[0], pair[1])).as("%s ~ %s", pair[0], pair[1]).isGreaterThanOrEqualTo(threshold);
		}
		// No deben pasar (palabras distintas).
		for (String[] pair : new String[][] { { "java", "javascript" }, { "java", "jira" }, { "react", "redux" },
				{ "node", "code" }, { "sql", "sqlite" }, { "ruta", "rust" }, { "rest", "restaurante" } }) {
			assertThat(similarity(pair[0], pair[1])).as("%s ~ %s", pair[0], pair[1]).isLessThan(threshold);
		}
		assertThat(similarity("datos", "dato")).as("singular y plural").isGreaterThanOrEqualTo(threshold);
	}

	@Test
	@DisplayName("004-31: palabras de 3 letras o menos no se buscan por parecido ('sqk' no encuentra SQL)")
	void t004_31_shortWordsAreExactOnly() {
		assertThat(list("?q=sqk").get("totalItems").asInt()).isZero();
		assertThat(list("?q=ph").get("totalItems").asInt()).isZero();
	}

	@Test
	@DisplayName("004-09: una palabra sin ningún parecido responde 200 con items vacío y totales en cero")
	void t004_09_noSimilarityIsEmptyNotAnError() {
		for (String query : List.of("?q=zzqxwv", "?q=xylophone%20qwerty", "?q=zzqxwv&sort=relevance")) {
			Response response = client().get("/api/v1/vacancies" + query);

			assertThat(response.status()).as(query).isEqualTo(200);
			assertThat(response.json().get("items")).as(query).isEmpty();
			assertThat(response.json().get("totalItems").asInt()).as(query).isZero();
			assertThat(response.json().get("totalPages").asInt()).as(query).isZero();
		}
	}

	@Test
	@DisplayName("004-31: el parecido es rápido con los datos de ejemplo (varias palabras, dos consultas por petición)")
	void t004_31_performanceIsReasonable() {
		String query = "?q=pyton%20javva%20bakend%20automatizasion%20react%20programacion&size=24";
		list(query); // calienta el contexto y el plan

		long start = System.nanoTime();
		for (int i = 0; i < 5; i++) {
			list(query);
		}
		long averageMillis = (System.nanoTime() - start) / 5 / 1_000_000;

		assertThat(averageMillis).as("milisegundos por petición con 6 palabras").isLessThan(1500);
	}

	// ------------------------------------------------------------------------------------------ 004-32

	@Test
	@DisplayName("004-32: la búsqueda mira también la descripción: palabras que solo están ahí encuentran la vacante")
	void t004_32_descriptionIsSearched() {
		// 'carrito' solo aparece en la descripción de "Practicante Frontend"; 'avances' y 'reunión' en la de Backend Java.
		assertThat(titles(list("?q=carrito"))).containsExactly("Practicante Frontend");
		assertThat(titles(list("?q=Carrito%20de%20compras"))).containsExactly("Practicante Frontend");
		assertThat(titles(list("?q=avances"))).containsExactly("Practicante Backend Java");
		assertThat(titles(list("?q=reuni%C3%B3n%20corta")).get(0)).as("con tilde y sin ella")
				.isEqualTo(titles(list("?q=reunion%20corta")).get(0));
		assertThat(titles(list("?q=se%20usar%20carrito"))).containsExactly("Practicante Frontend");
	}

	@Test
	@DisplayName("004-32: la descripción pesa como el resumen: menos que el título")
	void t004_32_descriptionWeighsLessThanTitle() {
		UUID inTitle = insertVacancy("Practicante carrito", "Resumen de prueba.",
				OffsetDateTime.now(ZoneOffset.UTC).minusDays(80), "OPEN", false);
		try {
			List<String> found = titles(list("?q=carrito"));

			assertThat(found).containsExactly("Practicante carrito", "Practicante Frontend");
		}
		finally {
			deleteVacancy(inTitle);
		}
	}

	@Test
	@DisplayName("004-32: la descripción no se devuelve en el listado")
	void t004_32_descriptionIsNotInTheCard() {
		JsonNode card = list("?q=carrito").get("items").get(0);

		assertThat(card.has("description")).isFalse();
	}

	private double similarity(String a, String b) {
		Double value = jdbc.queryForObject("SELECT similarity(?, ?)", Double.class, a, b);
		return value == null ? 0 : value;
	}

}
