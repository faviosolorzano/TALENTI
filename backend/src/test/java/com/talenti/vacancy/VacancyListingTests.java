package com.talenti.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.talenti.identity.service.LegalDocumentService;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/** Listado público de vacantes: forma, búsqueda, filtros, orden, paginación y errores (004-04 a 004-10). */
class VacancyListingTests extends VacancyIntegrationTest {

	@Autowired
	private LegalDocumentService legal;

	// ------------------------------------------------------------------------------------------ 004-04

	@Test
	@DisplayName("004-04: un visitante sin sesión recibe 200 con items, page, size, totalItems y totalPages, y ninguna cookie")
	void t004_04_anonymousListHasShapeAndNoCookie() {
		ApiClient visitor = client();

		Response response = visitor.get("/api/v1/vacancies");

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.setCookies()).as("Set-Cookie").isEmpty();
		assertThat(visitor.cookies()).isEmpty();
		JsonNode page = response.json();
		assertThat(page.get("page").asInt()).isEqualTo(1);
		assertThat(page.get("size").asInt()).isEqualTo(9);
		assertThat(page.get("totalItems").asInt()).isEqualTo(12);
		assertThat(page.get("totalPages").asInt()).isEqualTo(2);
		assertThat(page.get("items")).hasSize(9);
		JsonNode first = page.get("items").get(0);
		assertThat(first.propertyNames()).containsExactlyInAnyOrder("id", "title", "summary", "modality", "location",
				"hoursPerWeek", "publishedAt", "featured", "company", "skills");
		assertThat(first.get("company").propertyNames()).containsExactlyInAnyOrder("id", "name", "city");
		assertThat(first.get("skills").get(0).propertyNames()).containsExactlyInAnyOrder("slug", "name", "kind");
	}

	@Test
	@DisplayName("004-04: el listado solo trae vacantes abiertas (una cerrada no aparece)")
	void t004_04_onlyOpenVacancies() {
		UUID closed = insertVacancy("Vacante cerrada de prueba", "Ya no recibe postulaciones.",
				OffsetDateTime.now(ZoneOffset.UTC), "CLOSED", false);
		try {
			JsonNode page = list("?size=24");

			assertThat(page.get("totalItems").asInt()).isEqualTo(12);
			assertThat(titles(page)).doesNotContain("Vacante cerrada de prueba");
			assertThat(list("?q=cerrada").get("totalItems").asInt()).isZero();
		}
		finally {
			deleteVacancy(closed);
		}
	}

	@Test
	@DisplayName("004-04: cada tarjeta trae las habilidades obligatorias primero y sin porcentaje de compatibilidad")
	void t004_04_cardsListRequiredSkillsFirst() {
		JsonNode page = list("?q=backend");

		JsonNode card = page.get("items").get(0);
		assertThat(card.get("title").asString()).isEqualTo("Practicante Backend Java");
		List<String> kinds = new ArrayList<>();
		for (JsonNode skill : card.get("skills")) {
			kinds.add(skill.get("kind").asString());
		}
		assertThat(kinds).containsExactly("REQUIRED", "REQUIRED", "REQUIRED", "DESIRABLE", "DESIRABLE");
		assertThat(card.has("compatibility")).isFalse();
		assertThat(card.has("matchPercentage")).isFalse();
		assertThat(card.get("location").isNull()).as("una vacante remota no tiene ciudad").isTrue();
		assertThat(card.get("publishedAt").asString()).endsWith("Z");
	}

	@Test
	@DisplayName("004-04: con una cookie de sesión válida el cuerpo es idéntico y tampoco hay Set-Cookie")
	void t004_04_sessionDoesNotChangeTheBody() {
		ApiClient student = client();
		Response registered = student.post("/api/v1/auth/register", Map.of("email",
				"estudiante." + UUID.randomUUID().toString().substring(0, 12) + "@ejemplo.test", "password",
				"Aa1" + UUID.randomUUID().toString().replace("-", "").substring(0, 12), "legalAccepted", true,
				"legalVersion", legal.currentVersion()));
		assertThat(registered.status()).isEqualTo(201);
		assertThat(student.cookie("JSESSIONID")).as("sesión abierta").isNotNull();

		Response withSession = student.get("/api/v1/vacancies?size=24");
		Response anonymous = client().get("/api/v1/vacancies?size=24");

		assertThat(withSession.status()).isEqualTo(200);
		assertThat(withSession.setCookies()).as("Set-Cookie con sesión").isEmpty();
		assertThat(withSession.body()).isEqualTo(anonymous.body());
	}

	// ------------------------------------------------------------------------------------------ 004-06

	@Test
	@DisplayName("004-06: 'programacion' y 'PROGRAMACIÓN' encuentran lo mismo, sin importar tildes ni mayúsculas")
	void t004_06_accentsAndCaseAreIgnored() {
		// Tres lo dicen en el resumen y dos más solo en la descripción (004-32); los del resumen salen primero.
		List<String> inSummary = List.of("Practicante Backend Java", "Practicante iOS", "Practicante PHP y Laravel");
		List<String> inDescription = List.of("Practicante Frontend", "Practicante Full Stack Node.js");

		for (String query : List.of("?q=programacion", "?q=PROGRAMACI%C3%93N", "?q=Programaci%C3%B3n")) {
			List<String> found = titles(list(query));
			assertThat(found).as(query).hasSize(5);
			assertThat(found.subList(0, 3)).as(query).containsExactlyInAnyOrderElementsOf(inSummary);
			assertThat(found.subList(3, 5)).as(query).containsExactlyInAnyOrderElementsOf(inDescription);
		}
		// Y al revés: la consulta sin tilde contra un nombre con tilde ("Cóndor Datos").
		assertThat(titles(list("?q=condor"))).containsExactlyInAnyOrder("Practicante de Datos con SQL",
				"Practicante Python y automatización");
		assertThat(titles(list("?q=C%C3%93NDOR"))).hasSize(2);
	}

	@Test
	@DisplayName("004-06: la búsqueda mira título, resumen, empresa y habilidades (nombre o sinónimo)")
	void t004_06_searchesTitleSummaryCompanyAndSkills() {
		assertThat(titles(list("?q=calidad"))).as("título").contains("Practicante de Calidad");
		assertThat(titles(list("?q=reservas"))).as("descripción (004-32)").containsExactly("Practicante Backend Java");
		assertThat(titles(list("?q=repetitivas"))).as("resumen").containsExactly("Practicante Python y automatización");
		assertThat(titles(list("?q=misti"))).as("empresa").containsExactlyInAnyOrder("Practicante .NET",
				"Practicante PHP y Laravel");
		assertThat(titles(list("?q=laravel"))).as("nombre de habilidad").contains("Practicante PHP y Laravel");
		assertThat(titles(list("?q=Amazon%20Web%20Services")).get(0)).as("sinónimo de habilidad: sale primero")
				.isEqualTo("Practicante DevOps junior");
	}

	@Test
	@DisplayName("004-06: 'JS' encuentra las vacantes que piden JavaScript (por su sinónimo)")
	void t004_06_aliasJsFindsJavaScript() {
		List<String> found = titles(list("?q=JS"));

		// Piden JavaScript: Frontend, Full Stack y PHP (deseable). La búsqueda es por "contiene", así que también
		// salen las de React y Node.js, cuyos nombres o sinónimos llevan "js".
		assertThat(found).contains("Practicante Frontend", "Practicante Full Stack Node.js",
				"Practicante PHP y Laravel");
		assertThat(found).doesNotContain("Practicante Python y automatización", "Practicante iOS");
		assertThat(titles(list("?q=js"))).isEqualTo(found);
	}

	@Test
	@DisplayName("004-27: con varios términos basta uno (O); las que cumplen más salen primero")
	void t004_27_anyTermMatches() {
		List<String> backendJava = titles(list("?q=backend%20java"));
		assertThat(backendJava.get(0)).isEqualTo("Practicante Backend Java");
		assertThat(titles(list("?q=java%20backend")).get(0)).isEqualTo("Practicante Backend Java");
		// Empresa en un término y habilidad deseable en el otro: la que cumple los dos sale primero.
		assertThat(titles(list("?q=condor%20docker")).get(0)).isEqualTo("Practicante Python y automatización");
		// Un término sin ninguna coincidencia ya no vacía el resultado.
		List<String> backendSwift = titles(list("?q=backend%20swift"));
		assertThat(backendSwift).contains("Practicante Backend Java", "Practicante iOS");
		// Espacios de más y términos repetidos no cambian el resultado.
		assertThat(titles(list("?q=%20%20backend%20%20JAVA%20backend%20"))).isEqualTo(backendJava);
	}

	@Test
	@DisplayName("004-06: %, _, comillas y barra invertida se tratan como texto, no como comodines ni como SQL")
	void t004_06_specialCharactersAreText() {
		assertThat(list("?q=%25").get("totalItems").asInt()).as("% no es comodín").isZero();
		assertThat(list("?q=_").get("totalItems").asInt()).as("_ no es comodín").isZero();
		assertThat(list("?q=pract_cante").get("totalItems").asInt()).as("_ en medio de una palabra").isZero();
		assertThat(list("?q=%5C").get("totalItems").asInt()).as("barra invertida").isZero();
		assertThat(list("?q=O%27Reilly").get("totalItems").asInt()).as("comilla simple").isZero();
		assertThat(list("?q=%22").get("totalItems").asInt()).as("comilla doble").isZero();
		assertThat(list("?q=%27%3B%20DROP%20TABLE%20vacancy%3B%20--").get("totalItems").asInt()).isZero();
		assertThat(count("SELECT count(*) FROM vacancy")).as("la tabla sigue ahí").isGreaterThanOrEqualTo(12);

		// Y encuentran cuando de verdad están en el texto.
		UUID id = insertVacancy("Practicante 100% remoto_ya", "Descuento de O'Reilly.",
				OffsetDateTime.now(ZoneOffset.UTC), "OPEN", false);
		try {
			assertThat(titles(list("?q=100%25"))).containsExactly("Practicante 100% remoto_ya");
			assertThat(titles(list("?q=%25"))).containsExactly("Practicante 100% remoto_ya");
			assertThat(titles(list("?q=remoto_ya"))).containsExactly("Practicante 100% remoto_ya");
			assertThat(titles(list("?q=O%27Reilly"))).containsExactly("Practicante 100% remoto_ya");
		}
		finally {
			deleteVacancy(id);
		}
	}

	@Test
	@DisplayName("004-06: sin texto o con solo espacios no se filtra")
	void t004_06_blankQueryDoesNotFilter() {
		assertThat(list("").get("totalItems").asInt()).isEqualTo(12);
		assertThat(list("?q=").get("totalItems").asInt()).isEqualTo(12);
		assertThat(list("?q=%20%20%20").get("totalItems").asInt()).isEqualTo(12);
		assertThat(list("?q=%09%20").get("totalItems").asInt()).isEqualTo(12);
	}

	@Test
	@DisplayName("004-06: un carácter nulo o de control en q no rompe la consulta")
	void t004_06_controlCharactersAreSafe() {
		assertThat(list("?q=%00").get("totalItems").asInt()).as("NUL").isEqualTo(12);
		assertThat(titles(list("?q=backend%00java")).get(0)).as("NUL entre dos términos")
				.isEqualTo("Practicante Backend Java");
	}

	// ------------------------------------------------------------------------------------------ 004-07

	@Test
	@DisplayName("004-07: dentro de un mismo filtro basta una coincidencia (O)")
	void t004_07_sameFilterIsOr() {
		assertThat(list("?modality=REMOTE").get("totalItems").asInt()).isEqualTo(4);
		assertThat(list("?modality=REMOTE&modality=ONSITE").get("totalItems").asInt()).isEqualTo(7);
		assertThat(list("?modality=REMOTE&modality=HYBRID&modality=ONSITE").get("totalItems").asInt()).isEqualTo(12);
		assertThat(list("?location=Lima").get("totalItems").asInt()).isEqualTo(5);
		assertThat(list("?location=Lima&location=Arequipa").get("totalItems").asInt()).isEqualTo(8);
		assertThat(list("?skill=swift").get("totalItems").asInt()).isEqualTo(1);
		assertThat(list("?skill=swift&skill=kotlin").get("totalItems").asInt()).isEqualTo(2);
		// 'skill' mira obligatorias y deseables: Docker es deseable en 3 y obligatorio en 1.
		assertThat(titles(list("?skill=docker"))).containsExactlyInAnyOrder("Practicante Backend Java",
				"Practicante Python y automatización", "Practicante Full Stack Node.js", "Practicante DevOps junior");
	}

	@Test
	@DisplayName("004-07: entre filtros distintos deben cumplirse todos (Y) y totalItems refleja el filtro")
	void t004_07_differentFiltersAreAnd() {
		JsonNode page = list("?modality=REMOTE&skill=docker");
		assertThat(titles(page)).containsExactlyInAnyOrder("Practicante Backend Java",
				"Practicante Python y automatización", "Practicante DevOps junior");
		assertThat(page.get("totalItems").asInt()).isEqualTo(3);

		assertThat(list("?modality=HYBRID&location=Arequipa").get("totalItems").asInt()).isEqualTo(2);
		assertThat(list("?modality=HYBRID&modality=ONSITE&location=Lima&skill=git").get("totalItems").asInt())
				.isEqualTo(4);
		// Con texto, modalidad y ubicación a la vez.
		assertThat(titles(list("?q=practicante&modality=HYBRID&location=Lima"))).containsExactlyInAnyOrder(
				"Practicante Frontend", "Practicante Android", "Practicante de Datos con SQL");
		assertThat(list("?modality=REMOTE&location=Lima").get("totalItems").asInt())
				.as("una vacante remota no tiene ciudad").isZero();
	}

	@Test
	@DisplayName("004-07: la ubicación no distingue mayúsculas ni tildes y los valores repetidos se deduplican")
	void t004_07_locationIsTolerantAndDuplicatesCollapse() {
		assertThat(list("?location=lima").get("totalItems").asInt()).isEqualTo(5);
		assertThat(list("?location=AREQUIPA").get("totalItems").asInt()).isEqualTo(3);
		assertThat(list("?location=Lima&location=Lima&location=lima").get("totalItems").asInt()).isEqualTo(5);
		assertThat(list("?modality=REMOTE&modality=REMOTE").get("totalItems").asInt()).isEqualTo(4);
		assertThat(list("?skill=git&skill=git").get("totalItems").asInt()).isEqualTo(11);
	}

	// ------------------------------------------------------------------------------------------ 004-08

	@Test
	@DisplayName("004-08: por defecto 9 por página, 'recent' (publicación descendente) y una destacada no cambia su posición")
	void t004_08_defaultOrderIsRecentAndFeaturedDoesNotMove() {
		JsonNode first = list("");
		JsonNode second = list("?page=2");

		List<String> all = new ArrayList<>(titles(first));
		all.addAll(titles(second));
		assertThat(titles(first)).hasSize(9);
		assertThat(titles(second)).hasSize(3);
		assertThat(all).containsExactlyElementsOf(SEED_TITLES_RECENT_FIRST);
		// Hay destacadas en las posiciones 0, 2, 3 y 5, y no destacadas entre ellas: el orden es solo por fecha.
		List<Boolean> featured = new ArrayList<>();
		for (JsonNode item : first.get("items")) {
			featured.add(item.get("featured").asBoolean());
		}
		assertThat(featured.subList(0, 6)).containsExactly(true, false, true, true, false, true);
	}

	@Test
	@DisplayName("004-08: 'company' ordena por nombre de empresa (sin distinguir tildes) y luego por fecha")
	void t004_08_companySort() {
		JsonNode page = list("?sort=company&size=24");

		assertThat(titles(page)).containsExactly("Practicante Backend Java", "Practicante de Datos con SQL",
				"Practicante Python y automatización", "Practicante .NET", "Practicante PHP y Laravel",
				"Practicante React y TypeScript", "Practicante Frontend", "Practicante de Calidad",
				"Practicante Full Stack Node.js", "Practicante iOS", "Practicante Android",
				"Practicante DevOps junior");
		assertThat(titles(list("?sort=recent&size=24"))).containsExactlyElementsOf(SEED_TITLES_RECENT_FIRST);
	}

	@Test
	@DisplayName("004-08: las páginas no repiten ni omiten vacantes, en ambos órdenes y con distintos tamaños")
	void t004_08_pagesNeitherRepeatNorSkip() {
		for (String sort : List.of("recent", "company")) {
			List<String> reference = ids(list("?sort=" + sort + "&size=24"));
			assertThat(reference).hasSize(12);
			for (int size : List.of(1, 5, 7, 12)) {
				List<String> walked = new ArrayList<>();
				int totalPages = list("?sort=" + sort + "&size=" + size).get("totalPages").asInt();
				assertThat(totalPages).isEqualTo((12 + size - 1) / size);
				for (int page = 1; page <= totalPages; page++) {
					walked.addAll(ids(list("?sort=" + sort + "&size=" + size + "&page=" + page)));
				}
				assertThat(walked).as("sort=%s size=%d", sort, size).containsExactlyElementsOf(reference);
			}
		}
	}

	@Test
	@DisplayName("004-08: con la misma fecha de publicación, el id desempata y el orden no cambia entre páginas")
	void t004_08_ties() {
		OffsetDateTime same = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);
		UUID a = insertVacancy("Empate A", "Resumen.", same, "OPEN", false);
		UUID b = insertVacancy("Empate B", "Resumen.", same, "OPEN", true);
		try {
			Set<String> seen = new LinkedHashSet<>();
			for (int page = 1; page <= 14; page++) {
				List<String> ids = ids(list("?size=1&page=" + page));
				assertThat(ids).hasSize(1);
				assertThat(seen.add(ids.get(0))).as("sin repetir en la página %d", page).isTrue();
			}
			assertThat(seen).hasSize(14).contains(a.toString(), b.toString());
			// Dos consultas iguales devuelven el mismo orden.
			assertThat(ids(list("?size=24"))).isEqualTo(ids(list("?size=24")));
		}
		finally {
			deleteVacancy(a);
			deleteVacancy(b);
		}
	}

	@Test
	@DisplayName("004-08: size llega hasta 24 y page es desde 1")
	void t004_08_sizeAndPageBounds() {
		assertThat(list("?size=24").get("items")).hasSize(12);
		assertThat(list("?size=24").get("size").asInt()).isEqualTo(24);
		assertThat(list("?size=1").get("totalPages").asInt()).isEqualTo(12);
		assertThat(titles(list("?size=1&page=12"))).containsExactly("Practicante PHP y Laravel");
		assertThat(list("?page=1").get("page").asInt()).isEqualTo(1);
	}

	// ------------------------------------------------------------------------------------------ 004-09

	@Test
	@DisplayName("004-09: un filtro sin coincidencias responde 200 con items vacío y totales en cero")
	void t004_09_noMatches() {
		for (String query : List.of("?q=zzzzzz", "?location=Cusco", "?skill=no-existe", "?modality=ONSITE&skill=aws")) {
			JsonNode page = list(query);

			assertThat(page.get("items")).as(query).isEmpty();
			assertThat(page.get("totalItems").asInt()).as(query).isZero();
			assertThat(page.get("totalPages").asInt()).as(query).isZero();
		}
	}

	@Test
	@DisplayName("004-09: una página mayor que totalPages responde 200 con items vacío y los totales reales")
	void t004_09_pageBeyondTheEnd() {
		JsonNode page = list("?page=3");

		assertThat(page.get("items")).isEmpty();
		assertThat(page.get("page").asInt()).isEqualTo(3);
		assertThat(page.get("totalItems").asInt()).isEqualTo(12);
		assertThat(page.get("totalPages").asInt()).isEqualTo(2);
		assertThat(list("?page=2147483647").get("items")).isEmpty();
		assertThat(list("?page=2&size=24").get("items")).isEmpty();
	}

	// ------------------------------------------------------------------------------------------ 004-10

	@Test
	@DisplayName("004-10: modality, sort o skill mal formados o desconocidos responden 422 con errors[{field, rule}]")
	void t004_10_invalidFilters() {
		assertRejected("?modality=FOO", "modality", "invalid");
		assertRejected("?modality=remote", "modality", "invalid");
		assertRejected("?modality=REMOTE&modality=otra", "modality", "invalid");
		assertRejected("?sort=oldest", "sort", "invalid");
		assertRejected("?sort=RECENT", "sort", "invalid");
		assertRejected("?skill=Java", "skill", "invalid");
		assertRejected("?skill=a_b", "skill", "invalid");
		assertRejected("?skill=" + "a".repeat(41), "skill", "invalid");
		assertRejected("?skill=java%3B%20drop", "skill", "invalid");
		assertRejected("?location=" + "a".repeat(61), "location", "invalid");
		assertRejected("?location=Li%00ma", "location", "invalid");
	}

	@Test
	@DisplayName("004-10: page menor que 1 o no numérica, y size fuera de 1 a 24, responden 422")
	void t004_10_invalidPagination() {
		assertRejected("?page=0", "page", "out-of-range");
		assertRejected("?page=-1", "page", "out-of-range");
		assertRejected("?page=abc", "page", "invalid");
		assertRejected("?page=1.5", "page", "invalid");
		assertRejected("?page=99999999999999999999", "page", "invalid");
		assertRejected("?size=0", "size", "out-of-range");
		assertRejected("?size=25", "size", "out-of-range");
		assertRejected("?size=-3", "size", "out-of-range");
		assertRejected("?size=nueve", "size", "invalid");
	}

	@Test
	@DisplayName("004-10: q de más de 80 caracteres responde 422 sin repetir el texto; con 80 funciona")
	void t004_10_tooLongQuery() {
		String tooLong = "a".repeat(81);

		Response response = assertRejected("?q=" + tooLong, "q", "too-long");

		assertThat(response.body()).doesNotContain(tooLong);
		assertThat(response.json().get("errors").get(0).get("message").asString()).contains("80");
		assertThat(list("?q=" + "a".repeat(80)).get("totalItems").asInt()).isZero();
		// Los espacios sobrantes no cuentan: se colapsan antes de medir.
		assertThat(list("?q=" + "%20".repeat(100) + "backend").get("totalItems").asInt()).isEqualTo(1);
	}

	@Test
	@DisplayName("004-10: más de 6 términos responde 422 too-many-terms; 6 funcionan; las letras sueltas se ignoran")
	void t004_10_termLimit() {
		Response response = assertRejected("?q=uno%20dos%20tres%20cuatro%20cinco%20seis%20siete", "q", "too-many-terms");
		assertThat(response.body()).doesNotContain("cinco");

		assertThat(list("?q=zzuno%20zzdos%20zztres%20zzcuatro%20zzcinco%20zzseis").get("totalItems").asInt()).isZero();
		// Letras sueltas: no filtran ni cuentan para el límite.
		assertThat(list("?q=a").get("totalItems").asInt()).isEqualTo(12);
		assertThat(titles(list("?q=a%20b%20backend%20c%20d%20e%20f%20g"))).containsExactly("Practicante Backend Java");
		// Un símbolo suelto sigue buscándose como texto.
		assertThat(list("?q=%25%20_").get("totalItems").asInt()).isZero();
	}

	@Test
	@DisplayName("004-10: más de 80 caracteres con espacios internos responde 422, aunque tenga pocas palabras")
	void t004_10_tooLongWithInnerSpaces() {
		String spaced = "programacion%20backend%20fullstack%20".repeat(3).substring(0, 3 * 37 - 3);

		Response response = assertRejected("?q=" + spaced, "q", "too-long");

		assertThat(response.body()).doesNotContain("programacion");
	}

	@Test
	@DisplayName("004-10: más de 10 valores distintos en un filtro responden 422; 10 son válidos y los repetidos se cuentan una vez")
	void t004_10_tooManyFilterValues() {
		StringBuilder skills = new StringBuilder("?");
		StringBuilder locations = new StringBuilder("?");
		for (int i = 1; i <= 11; i++) {
			skills.append("skill=s").append(i).append('&');
			locations.append("location=Ciudad").append(i).append('&');
		}
		assertRejected(skills.toString(), "skill", "too-many");
		assertRejected(locations.toString(), "location", "too-many");

		assertThat(list(skills.toString().replace("skill=s11&", "")).get("totalItems").asInt())
				.as("10 habilidades inexistentes pero bien formadas").isZero();
		assertThat(list("?" + "skill=git&".repeat(30)).get("totalItems").asInt()).isEqualTo(11);
	}

	@Test
	@DisplayName("004-10: un skill o location bien formado pero inexistente responde 200 vacío, no 422")
	void t004_10_unknownButWellFormedIsEmpty() {
		assertThat(list("?skill=cobol").get("totalItems").asInt()).isZero();
		assertThat(list("?location=Atlantida").get("totalItems").asInt()).isZero();
	}

	@Test
	@DisplayName("004-10: varios errores salen juntos en un solo 422 y no se ejecuta la búsqueda")
	void t004_10_severalErrorsAtOnce() {
		Response response = client().get("/api/v1/vacancies?modality=FOO&page=0&size=99&q=algo");

		assertThat(response.status()).isEqualTo(422);
		List<String> fields = new ArrayList<>();
		for (JsonNode error : response.json().get("errors")) {
			fields.add(error.get("field").asString() + ":" + error.get("rule").asString());
		}
		assertThat(fields).containsExactlyInAnyOrder("modality:invalid", "page:out-of-range", "size:out-of-range");
	}

	private Response assertRejected(String query, String field, String rule) {
		Response response = client().get("/api/v1/vacancies" + query);

		assertThat(response.status()).as("GET /vacancies" + query).isEqualTo(422);
		assertThat(response.header("Content-Type")).startsWith("application/problem+json");
		JsonNode body = response.json();
		assertThat(body.get("code").asString()).isEqualTo("validation-failed");
		assertThat(body.get("status").asInt()).isEqualTo(422);
		assertThat(body.get("errors")).anySatisfy(error -> {
			assertThat(error.get("field").asString()).isEqualTo(field);
			assertThat(error.get("rule").asString()).isEqualTo(rule);
			assertThat(error.get("message").asString()).isNotBlank();
		});
		assertThat(response.setCookies()).isEmpty();
		assertThat(response.header("Cache-Control")).as("un error no se guarda en caché").contains("no-store");
		assertThat(response.header("ETag")).isNull();
		return response;
	}

}
