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

import tools.jackson.databind.JsonNode;

/** Detalle, destacadas y opciones de filtro (004-11 a 004-13). */
class VacancyDetailAndFeaturedTests extends VacancyIntegrationTest {

	private static final String UNKNOWN_ID = "99999999-9999-4999-8999-999999999999";

	// ------------------------------------------------------------------------------------------ 004-11

	@Test
	@DisplayName("004-11: el detalle trae descripción, condiciones, la empresa completa y los requisitos por separado")
	void t004_11_detailOfAnOpenVacancy() {
		Response response = client().get("/api/v1/vacancies/" + BACKEND_JAVA);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.setCookies()).isEmpty();
		JsonNode vacancy = response.json();
		assertThat(vacancy.get("id").asString()).isEqualTo(BACKEND_JAVA);
		assertThat(vacancy.get("title").asString()).isEqualTo("Practicante Backend Java");
		assertThat(vacancy.get("status").asString()).isEqualTo("OPEN");
		assertThat(vacancy.get("description").asString()).isNotBlank().contains("Spring Boot");
		assertThat(vacancy.get("summary").asString()).isNotBlank();
		assertThat(vacancy.get("modality").asString()).isEqualTo("REMOTE");
		assertThat(vacancy.get("location").isNull()).isTrue();
		assertThat(vacancy.get("hoursPerWeek").asInt()).isEqualTo(20);
		assertThat(vacancy.get("publishedAt").asString()).endsWith("Z");
		assertThat(vacancy.get("featured").asBoolean()).isTrue();
		JsonNode company = vacancy.get("company");
		assertThat(company.get("id").asString()).isEqualTo(ANDES_TECH);
		assertThat(company.get("name").asString()).isEqualTo("Andes Tech");
		assertThat(company.get("city").asString()).isEqualTo("Lima");
		assertThat(company.get("shortDescription").asString()).isNotBlank();
	}

	@Test
	@DisplayName("004-11: los requisitos obligatorios y deseables vienen separados, con habilidad y nivel mínimo (RN-01)")
	void t004_11_requirementsAreSeparatedWithLevels() {
		JsonNode requirements = client().get("/api/v1/vacancies/" + BACKEND_JAVA).json().get("requirements");

		assertThat(requirements.propertyNames()).containsExactlyInAnyOrder("required", "desirable");
		assertThat(levels(requirements.get("required"))).containsExactly("java:2", "sql:2", "spring-boot:1");
		assertThat(levels(requirements.get("desirable"))).containsExactly("docker:1", "git:1");
		JsonNode java = requirements.get("required").get(0);
		assertThat(java.get("skill").propertyNames()).containsExactlyInAnyOrder("slug", "name");
		assertThat(java.get("skill").get("name").asString()).isEqualTo("Java");
		assertThat(java.get("minLevel").asInt()).isEqualTo(2);
	}

	@Test
	@DisplayName("004-11: una vacante cerrada responde 200 con status CLOSED y no aparece en los listados")
	void t004_11_closedVacancyStillHasDetail() {
		UUID id = insertOpenVacancy("Vacante que se cierra");
		try {
			assertThat(client().get("/api/v1/vacancies/" + id).json().get("status").asString()).isEqualTo("OPEN");
			assertThat(titles(list("?q=se%20cierra"))).containsExactly("Vacante que se cierra");

			jdbc.update("UPDATE vacancy SET status = 'CLOSED' WHERE id = ?", id);

			Response detail = client().get("/api/v1/vacancies/" + id);
			assertThat(detail.status()).isEqualTo(200);
			assertThat(detail.json().get("status").asString()).isEqualTo("CLOSED");
			assertThat(detail.json().get("title").asString()).isEqualTo("Vacante que se cierra");
			assertThat(list("?q=se%20cierra").get("items")).isEmpty();
			assertThat(list("").get("totalItems").asInt()).isEqualTo(12);
		}
		finally {
			deleteVacancy(id);
		}
	}

	@Test
	@DisplayName("004-11: un id inexistente o mal formado responde 404 vacancy-not-found, sin distinguirlos")
	void t004_11_notFound() {
		Response unknown = client().get("/api/v1/vacancies/" + UNKNOWN_ID);
		Response malformed = client().get("/api/v1/vacancies/no-es-un-id");
		Response sql = client().get("/api/v1/vacancies/1%27%20OR%20%271%27=%271");

		for (Response response : List.of(unknown, malformed, sql)) {
			assertThat(response.status()).isEqualTo(404);
			assertThat(response.header("Content-Type")).startsWith("application/problem+json");
			assertThat(response.json().get("code").asString()).isEqualTo("vacancy-not-found");
			assertThat(response.json().get("title").asString()).isEqualTo("Vacante no encontrada");
			assertThat(response.json().get("detail").asString()).isEqualTo(unknown.json().get("detail").asString());
			assertThat(response.setCookies()).isEmpty();
			assertThat(response.header("Cache-Control")).contains("no-store");
			assertThat(response.header("ETag")).isNull();
		}
	}

	@Test
	@DisplayName("004-11: las rutas fijas 'featured' y 'filters' no se confunden con un id")
	void t004_11_fixedRoutesAreNotIds() {
		assertThat(client().get("/api/v1/vacancies/featured").json().has("items")).isTrue();
		assertThat(client().get("/api/v1/vacancies/filters").json().has("openCount")).isTrue();
	}

	// ------------------------------------------------------------------------------------------ 004-12

	@Test
	@DisplayName("004-12: las vacantes destacadas son solo las abiertas y destacadas, de la más reciente a la más antigua")
	void t004_12_featuredVacancies() {
		JsonNode featured = client().get("/api/v1/vacancies/featured").json();

		assertThat(featured.propertyNames()).containsExactly("items");
		assertThat(titles(featured)).containsExactly("Practicante DevOps junior", "Practicante Backend Java",
				"Practicante Frontend", "Practicante de Datos con SQL");
		for (JsonNode item : featured.get("items")) {
			assertThat(item.get("featured").asBoolean()).isTrue();
		}
		assertThat(titles(client().get("/api/v1/vacancies/featured?limit=2").json()))
				.containsExactly("Practicante DevOps junior", "Practicante Backend Java");
		assertThat(client().get("/api/v1/vacancies/featured?limit=8").json().get("items")).hasSize(4);
	}

	@Test
	@DisplayName("004-12: una vacante destacada que se cierra deja de salir en las destacadas")
	void t004_12_closedFeaturedDisappears() {
		UUID id = insertVacancy("Destacada nueva", "Resumen.", OffsetDateTime.now(ZoneOffset.UTC), "OPEN", true);
		try {
			assertThat(titles(client().get("/api/v1/vacancies/featured").json()).get(0)).isEqualTo("Destacada nueva");

			jdbc.update("UPDATE vacancy SET status = 'CLOSED' WHERE id = ?", id);

			assertThat(titles(client().get("/api/v1/vacancies/featured?limit=8").json()))
					.doesNotContain("Destacada nueva").hasSize(4);
		}
		finally {
			deleteVacancy(id);
		}
	}

	@Test
	@DisplayName("004-12: limit fuera de 1 a 8 o no numérico responde 422")
	void t004_12_invalidLimit() {
		for (String route : List.of("/api/v1/vacancies/featured", "/api/v1/companies/featured")) {
			for (String limit : List.of("0", "9", "-1", "abc", "2.5")) {
				Response response = client().get(route + "?limit=" + limit);

				assertThat(response.status()).as(route + " limit=" + limit).isEqualTo(422);
				assertThat(response.json().get("code").asString()).isEqualTo("validation-failed");
				assertThat(response.json().get("errors").get(0).get("field").asString()).isEqualTo("limit");
			}
		}
	}

	@Test
	@DisplayName("004-12: las empresas destacadas traen openVacancies y van de la de vacante más reciente a la más antigua")
	void t004_12_featuredCompanies() {
		JsonNode companies = client().get("/api/v1/companies/featured").json();

		assertThat(companies.propertyNames()).containsExactly("items");
		List<String> names = new ArrayList<>();
		for (JsonNode item : companies.get("items")) {
			names.add(item.get("name").asString());
		}
		assertThat(names).containsExactly("Urpi Cloud", "Nodo Digital", "Andes Tech", "Cóndor Datos");
		JsonNode nexa = companies.get("items").get(1);
		assertThat(nexa.propertyNames()).containsExactlyInAnyOrder("id", "name", "city", "shortDescription",
				"openVacancies");
		assertThat(nexa.get("openVacancies").asInt()).as("Nodo tiene 2 vacantes abiertas").isEqualTo(2);
		assertThat(companies.get("items").get(0).get("openVacancies").asInt()).isEqualTo(1);
		assertThat(client().get("/api/v1/companies/featured?limit=2").json().get("items")).hasSize(2);
		assertThat(client().get("/api/v1/companies/featured?limit=8").json().get("items")).hasSize(4);
	}

	@Test
	@DisplayName("004-12: una empresa destacada sin vacantes abiertas no sale; otra sin marcar tampoco")
	void t004_12_featuredCompanyNeedsAnOpenVacancy() {
		UUID empty = UUID.randomUUID();
		jdbc.update("INSERT INTO company (id, name, city, short_description, featured) VALUES (?, ?, 'Lima', 'Sin vacantes.', true)",
				empty, "Empresa sin vacantes " + empty);
		try {
			List<String> names = new ArrayList<>();
			for (JsonNode item : client().get("/api/v1/companies/featured?limit=8").json().get("items")) {
				names.add(item.get("name").asString());
			}

			assertThat(names).hasSize(4).noneMatch(name -> name.startsWith("Empresa sin vacantes"))
					.doesNotContain("Punto Código", "Ruta Labs", "Quipu Systems", "Misti Software");
		}
		finally {
			jdbc.update("DELETE FROM company WHERE id = ?", empty);
		}
	}

	// ------------------------------------------------------------------------------------------ 004-13

	@Test
	@DisplayName("004-13: filters devuelve openCount y las modalidades, ubicaciones y habilidades con su conteo")
	void t004_13_filters() {
		JsonNode filters = client().get("/api/v1/vacancies/filters").json();

		assertThat(filters.propertyNames()).containsExactly("openCount", "modalities", "locations", "skills");
		assertThat(filters.get("openCount").asInt()).isEqualTo(12);
		assertThat(pairs(filters.get("modalities"), "value")).containsExactly("REMOTE=4", "HYBRID=5", "ONSITE=3");
		assertThat(pairs(filters.get("locations"), "value")).containsExactly("Lima=5", "Arequipa=3");
		List<String> skills = pairs(filters.get("skills"), "slug");
		assertThat(skills).contains("git=11", "sql=7", "java=1", "swift=1", "docker=4", "javascript=3", "aws=1");
		assertThat(skills).as("solo habilidades con al menos una vacante").doesNotContain("vue=0", "mysql=0")
				.noneMatch(pair -> pair.startsWith("vue=") || pair.startsWith("mysql="));
		JsonNode git = filters.get("skills").get(0);
		assertThat(git.propertyNames()).containsExactlyInAnyOrder("slug", "name", "count");
		assertThat(git.get("name").asString()).isEqualTo("Git");
	}

	@Test
	@DisplayName("004-13: openCount es el conteo real y baja al cerrar una vacante; los conteos incluyen solo abiertas")
	void t004_13_openCountDropsWhenOneCloses() {
		UUID id = insertOpenVacancy("Vacante temporal");
		try {
			JsonNode before = client().get("/api/v1/vacancies/filters").json();
			assertThat(before.get("openCount").asInt()).isEqualTo(13);
			assertThat(pairs(before.get("locations"), "value")).contains("Lima=6");

			jdbc.update("UPDATE vacancy SET status = 'CLOSED' WHERE id = ?", id);

			JsonNode after = client().get("/api/v1/vacancies/filters").json();
			assertThat(after.get("openCount").asInt()).isEqualTo(12);
			assertThat(pairs(after.get("locations"), "value")).contains("Lima=5");
		}
		finally {
			deleteVacancy(id);
		}
	}

	private static List<String> levels(JsonNode requirements) {
		List<String> levels = new ArrayList<>();
		for (JsonNode requirement : requirements) {
			levels.add(requirement.get("skill").get("slug").asString() + ":" + requirement.get("minLevel").asInt());
		}
		return levels;
	}

	/** "valor=conteo" por cada opción, para comparar con claridad. */
	private static List<String> pairs(JsonNode options, String key) {
		List<String> pairs = new ArrayList<>();
		for (JsonNode option : options) {
			pairs.add(option.get(key).asString() + "=" + option.get("count").asInt());
		}
		return pairs;
	}

}
