package com.talenti.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/**
 * Las tres rutas de {@code /compatibility} contra PostgreSQL real y los datos de ejemplo (007-14 a 007-16, 007-02,
 * 007-06, 007-12, 007-13).
 */
class CompatibilityApiTests extends MatchingIntegrationTest {

	private static List<String> elements(JsonNode list, String field) {
		List<String> values = new ArrayList<>();
		for (JsonNode item : list) {
			values.add(item.get(field).asString());
		}
		return values;
	}

	// ------------------------------------------------------------------------------------------ 007-14

	@Test
	@DisplayName("007-14: el detalle de 'Practicante Backend Java' con el perfil del ejemplo da 81 sin similitud, base 100, HIGH, con el contrato completo")
	void t007_14_backendJavaExample() {
		ApiClient api = exampleStudent();

		Response response = api.get(COMPATIBILITY + "/vacancies/" + BACKEND_JAVA);

		assertThat(response.status()).isEqualTo(200);
		assertPrivate(response);
		JsonNode body = response.json();
		assertThat(body.get("vacancyId").asString()).isEqualTo(BACKEND_JAVA);
		assertThat(body.get("status").asString()).isEqualTo("CALCULATED");
		assertThat(body.get("percent").asInt()).isEqualTo(81);
		assertThat(body.get("level").asString()).isEqualTo("HIGH");
		assertThat(body.get("capped").asBoolean()).isFalse();
		assertThat(body.get("reasons")).isEmpty();
		assertThat(body.get("informationBasis").asInt()).isEqualTo(100);

		JsonNode components = body.get("components");
		assertThat(elements(components, "key")).containsExactly("REQUIRED", "DESIRABLE", "CONDITIONS", "SIMILARITY");
		assertThat(components.get(0).get("weight").asInt()).isEqualTo(50);
		assertThat(components.get(0).get("score").asDouble()).isEqualTo(0.833);
		assertThat(components.get(1).get("score").asDouble()).isEqualTo(0.5);
		assertThat(components.get(2).get("score").asDouble()).isEqualTo(1.0);
		assertThat(components.get(3).get("evaluable").asBoolean()).isFalse();
		assertThat(components.get(3).get("score").isNull()).isTrue();

		JsonNode formula = body.get("formula");
		assertThat(formula.get("version").asString()).isEqualTo("1");
		assertThat(formula.get("weights").get("required").asInt()).isEqualTo(50);
		assertThat(formula.get("weights").get("desirable").asInt()).isEqualTo(15);
		assertThat(formula.get("weights").get("conditions").asInt()).isEqualTo(20);
		assertThat(formula.get("weights").get("similarity").asInt()).isEqualTo(15);
		assertThat(formula.get("cap").asInt()).isEqualTo(69);
		assertThat(formula.get("minEvaluableWeight").asInt()).isEqualTo(50);
		assertThat(formula.get("similarityEnabled").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("007-12: matches, gaps y missingInfo traen la evidencia de cada requisito (tipo, nivel pedido, nivel del perfil, DECLARED) y de cada condición")
	void t007_12_listsWithEvidence() {
		JsonNode body = detail(exampleStudent(), BACKEND_JAVA);

		JsonNode matches = body.get("matches");
		assertThat(matches).anySatisfy(item -> {
			assertThat(item.get("type").asString()).isEqualTo("REQUIREMENT");
			assertThat(item.get("skill").get("slug").asString()).isEqualTo("java");
			assertThat(item.get("skill").get("name").asString()).isEqualTo("Java");
			assertThat(item.get("requirementKind").asString()).isEqualTo("REQUIRED");
			assertThat(item.get("requiredLevel").asInt()).isEqualTo(2);
			assertThat(item.get("profileLevel").asInt()).isEqualTo(2);
			assertThat(item.get("outcome").asString()).isEqualTo("MATCH");
			assertThat(item.get("evidence").asString()).isEqualTo("DECLARED");
		});
		assertThat(matches).anySatisfy(item -> {
			assertThat(item.get("type").asString()).isEqualTo("CONDITION");
			assertThat(item.get("factor").asString()).isEqualTo("MODALITY");
			assertThat(item.get("vacancyValue").asString()).isEqualTo("REMOTE");
			assertThat(item.get("profileValue").asString()).isEqualTo("REMOTE");
		});
		JsonNode gaps = body.get("gaps");
		assertThat(gaps).hasSize(2);
		assertThat(gaps.get(0).get("skill").get("slug").asString()).isEqualTo("sql");
		assertThat(gaps.get(0).get("outcome").asString()).isEqualTo("PARTIAL");
		assertThat(gaps.get(0).get("requiredLevel").asInt()).isEqualTo(2);
		assertThat(gaps.get(0).get("profileLevel").asInt()).isEqualTo(1);
		assertThat(gaps.get(1).get("skill").get("slug").asString()).isEqualTo("docker");
		assertThat(gaps.get(1).get("outcome").asString()).isEqualTo("MISSING");
		assertThat(gaps.get(1).get("requirementKind").asString()).isEqualTo("DESIRABLE");
		assertThat(gaps.get(1).get("profileLevel").isNull()).isTrue();
		// La ubicación no aplica en una vacante remota: no aparece en ninguna lista.
		assertThat(body.toString()).doesNotContain("\"factor\":\"LOCATION\"");
		assertThat(body.get("missingInfo")).isEmpty();
	}

	@Test
	@DisplayName("007-13: la explicación viene como TEMPLATE con versión y texto en español, sin IA y sin datos personales")
	void t007_13_explanation() {
		RegisteredStudent student = exampleStudentRegistered();

		JsonNode explanation = detail(student.api(), BACKEND_JAVA).get("explanation");

		assertThat(explanation.get("source").asString()).isEqualTo("TEMPLATE");
		assertThat(explanation.get("version").asString()).isEqualTo("1");
		assertThat(explanation.get("text").asString()).contains("Cumples 2 de 3 requisitos obligatorios.")
				.contains("SQL").doesNotContain(student.email()).doesNotContain("Lima");
	}

	@Test
	@DisplayName("007-05: un obligatorio ausente aparece como MISSING y el máximo es 69; el aviso del tope va en la explicación")
	void t007_05_missingRequiredIsCapped() {
		// Practicante Backend Java sin Spring Boot ni SQL: A = 1/3, el resto perfecto.
		ApiClient api = student();
		ok(api.put(PROFILE + "/skills", skills(skill("java", 3), skill("git", 3), skill("docker", 3))));
		ok(api.put(PROFILE + "/availability",
				body("hoursPerWeek", 40, "acceptedModalities", List.of("REMOTE", "HYBRID", "ONSITE"), "location", "Lima")));

		JsonNode body = detail(api, BACKEND_JAVA);

		assertThat(body.get("status").asString()).isEqualTo("CALCULATED");
		// (1/3*50 + 15 + 20) / 85 = 60,8 -> 61: el tope no bajó la cifra
		assertThat(body.get("percent").asInt()).isEqualTo(61);
		assertThat(body.get("capped").asBoolean()).isFalse();
		assertThat(body.get("level").asString()).isEqualTo("MEDIUM");
		assertThat(elements(body.get("gaps"), "outcome")).contains("MISSING");
		assertThat(body.get("explanation").get("text").asString()).contains("máximo posible")
				.contains("69 %");
	}

	@Test
	@DisplayName("007-06: un perfil sin habilidades recibe INSUFFICIENT con NO_SKILLS, sin porcentaje ni veredicto, y la lista de datos que faltan")
	void t007_06_noSkills() {
		JsonNode body = detail(student(), BACKEND_JAVA);

		assertThat(body.get("status").asString()).isEqualTo("INSUFFICIENT");
		assertThat(body.get("reasons").get(0).asString()).isEqualTo("NO_SKILLS");
		assertThat(body.get("percent").isNull()).isTrue();
		assertThat(body.get("level").isNull()).isTrue();
		assertThat(body.get("informationBasis").isNull()).isTrue();
		assertThat(body.get("capped").asBoolean()).isFalse();
		assertThat(body.get("missingInfo").get(0).get("type").asString()).isEqualTo("PROFILE");
		assertThat(body.get("missingInfo").get(0).get("key").asString()).isEqualTo("SKILLS");
		assertThat(body.get("explanation").get("source").asString()).isEqualTo("TEMPLATE");
		assertThat(body.get("formula").get("version").asString()).isEqualTo("1");
	}

	@Test
	@DisplayName("007-06: con una sola habilidad y sin condiciones declaradas se calcula con 'Basado en {n} %' y los datos que faltan")
	void t007_06_partialInformation() {
		ApiClient api = student();
		ok(api.put(PROFILE + "/skills", skills(skill("java", 2), skill("spring-boot", 1), skill("sql", 2))));

		JsonNode body = detail(api, BACKEND_JAVA);

		assertThat(body.get("status").asString()).isEqualTo("CALCULATED");
		assertThat(body.get("informationBasis").asInt()).isLessThan(100);
		assertThat(elements(body.get("missingInfo"), "factor")).containsExactlyInAnyOrder("MODALITY", "AVAILABILITY");
		assertThat(body.get("missingInfo").get(0).get("profileValue").isNull()).isTrue();
	}

	@Test
	@DisplayName("007-06: una vacante sin requisitos es INSUFFICIENT con VACANCY_WITHOUT_REQUIREMENTS")
	void t007_06_vacancyWithoutRequirements() {
		UUID id = insertVacancyWithJava("Vacante sin requisitos 007", OffsetDateTime.now(ZoneOffset.UTC).minusHours(2),
				"OPEN", UUID.randomUUID(), false);
		try {
			JsonNode body = detail(exampleStudent(), id.toString());

			assertThat(body.get("status").asString()).isEqualTo("INSUFFICIENT");
			assertThat(body.get("reasons").get(0).asString()).isEqualTo("VACANCY_WITHOUT_REQUIREMENTS");
			assertThat(body.get("percent").isNull()).isTrue();
		}
		finally {
			deleteVacancy(id);
		}
	}

	@Test
	@DisplayName("007-14: una vacante inexistente, cerrada o con id mal formado responde el mismo 404 vacancy-not-found, privado")
	void t007_14_notFound() {
		ApiClient api = exampleStudent();
		UUID closed = insertVacancyWithJava("Vacante cerrada 007", OffsetDateTime.now(ZoneOffset.UTC).minusHours(2),
				"CLOSED", UUID.randomUUID(), true);
		try {
			List<Response> responses = List.of(api.get(COMPATIBILITY + "/vacancies/" + UUID.randomUUID()),
					api.get(COMPATIBILITY + "/vacancies/" + closed), api.get(COMPATIBILITY + "/vacancies/abc"),
					api.get(COMPATIBILITY + "/vacancies/12345"));

			for (Response response : responses) {
				assertThat(response.status()).isEqualTo(404);
				assertThat(response.json().get("code").asString()).isEqualTo("vacancy-not-found");
				assertThat(response.json().get("title").asString()).isEqualTo("Vacante no encontrada");
				assertPrivate(response);
			}
			assertThat(responses.get(0).json().get("detail")).isEqualTo(responses.get(1).json().get("detail"));
			assertThat(responses.get(0).json().get("detail")).isEqualTo(responses.get(2).json().get("detail"));
		}
		finally {
			deleteVacancy(closed);
		}
	}

	// ------------------------------------------------------------------------------------------ 007-15

	@Test
	@DisplayName("007-15: recommended ordena por porcentaje descendente, a igualdad la más reciente y luego el id, con las INSUFFICIENT al final")
	void t007_15_orderingTiesAndInsufficientLast() {
		OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1);
		UUID older = insertVacancyWithJava("Empate antiguo 007", base.minusHours(5), "OPEN",
				UUID.fromString("00000000-0000-4000-8000-0000000000a2"), true);
		UUID newerHigherId = insertVacancyWithJava("Empate reciente 007 B", base, "OPEN",
				UUID.fromString("00000000-0000-4000-8000-0000000000b2"), true);
		UUID newerLowerId = insertVacancyWithJava("Empate reciente 007 A", base, "OPEN",
				UUID.fromString("00000000-0000-4000-8000-0000000000a3"), true);
		UUID insufficient = insertVacancyWithJava("Sin requisitos 007", base.plusHours(1), "OPEN",
				UUID.fromString("00000000-0000-4000-8000-0000000000c1"), false);
		try {
			ApiClient api = exampleStudent();

			JsonNode page = ok(api.get(COMPATIBILITY + "/recommended?size=24"));

			assertThat(page.get("profile").get("sufficient").asBoolean()).isTrue();
			List<String> ids = vacancyIds(page);
			// El orden completo respeta la regla: porcentaje desc, publishedAt desc, id.
			List<JsonNode> items = new ArrayList<>();
			page.get("items").forEach(items::add);
			for (int i = 1; i < items.size(); i++) {
				JsonNode previous = items.get(i - 1).get("compatibility");
				JsonNode current = items.get(i).get("compatibility");
				boolean previousInsufficient = previous.get("status").asString().equals("INSUFFICIENT");
				boolean currentInsufficient = current.get("status").asString().equals("INSUFFICIENT");
				assertThat(previousInsufficient && !currentInsufficient).as("las insuficientes van al final").isFalse();
				if (!previousInsufficient && !currentInsufficient) {
					assertThat(previous.get("percent").asInt()).isGreaterThanOrEqualTo(current.get("percent").asInt());
				}
			}
			// Entre las tres con el mismo porcentaje: la más reciente primero y, empatadas, el id menor.
			List<String> tied = ids.stream().filter(List.of(older.toString(), newerHigherId.toString(),
					newerLowerId.toString())::contains).toList();
			assertThat(tied).containsExactly(newerLowerId.toString(), newerHigherId.toString(), older.toString());
			assertThat(itemFor(page, older.toString()).get("compatibility").get("percent").asInt())
					.isEqualTo(itemFor(page, newerLowerId.toString()).get("compatibility").get("percent").asInt());
			// La insuficiente va después de todas las calculadas.
			assertThat(ids.indexOf(insufficient.toString())).isGreaterThan(ids.indexOf(older.toString()));
			assertThat(itemFor(page, insufficient.toString()).get("compatibility").get("percent").isNull()).isTrue();
			assertThat(items.get(items.size() - 1).get("compatibility").get("status").asString())
					.isEqualTo("INSUFFICIENT");
			// Cada elemento trae la tarjeta de 004 y el resumen.
			JsonNode first = items.get(0);
			assertThat(first.get("vacancy").propertyNames()).contains("id", "title", "summary", "modality",
					"location", "hoursPerWeek", "publishedAt", "featured", "company", "skills");
			assertThat(first.get("compatibility").propertyNames()).containsExactly("vacancyId", "status",
					"percent", "level", "capped");
			assertPrivateLastResponse(api);
		}
		finally {
			for (UUID id : List.of(older, newerHigherId, newerLowerId, insufficient)) {
				deleteVacancy(id);
			}
		}
	}

	private void assertPrivateLastResponse(ApiClient api) {
		assertPrivate(api.get(COMPATIBILITY + "/recommended"));
	}

	@Test
	@DisplayName("007-15: 'Practicante Backend Java' queda primero entre las recomendadas del perfil del ejemplo y la destacada no mueve el orden")
	void t007_15_seedOrderAndFeaturedDoNotMatter() {
		ApiClient api = exampleStudent();

		JsonNode page = ok(api.get(COMPATIBILITY + "/recommended?size=24"));

		assertThat(page.get("totalItems").asInt()).isGreaterThanOrEqualTo(12);
		assertThat(vacancyIds(page).get(0)).isEqualTo(BACKEND_JAVA);
		assertThat(page.get("items").get(0).get("compatibility").get("percent").asInt()).isEqualTo(81);
		// El orden por porcentaje no depende de "destacada": hay destacadas y no destacadas en cualquier posición.
		List<Integer> percents = new ArrayList<>();
		page.get("items").forEach(item -> {
			JsonNode summary = item.get("compatibility");
			if (summary.get("percent").isInt()) {
				percents.add(summary.get("percent").asInt());
			}
		});
		assertThat(percents).isSortedAccordingTo(Comparator.reverseOrder());
	}

	@Test
	@DisplayName("007-15: paginación como 004-08: por defecto 9, máximo 24, páginas que suman todo y una página fuera de rango vacía")
	void t007_15_pagination() {
		ApiClient api = exampleStudent();

		JsonNode first = ok(api.get(COMPATIBILITY + "/recommended"));
		JsonNode all = ok(api.get(COMPATIBILITY + "/recommended?size=24"));
		List<String> pages = new ArrayList<>();
		for (int page = 1; page <= 4; page++) {
			pages.addAll(vacancyIds(ok(api.get(COMPATIBILITY + "/recommended?page=" + page + "&size=4"))));
		}
		JsonNode beyond = ok(api.get(COMPATIBILITY + "/recommended?page=99&size=4"));

		assertThat(first.get("size").asInt()).isEqualTo(9);
		assertThat(first.get("page").asInt()).isEqualTo(1);
		assertThat(first.get("items")).hasSize(9);
		assertThat(first.get("totalItems").asLong()).isEqualTo(all.get("totalItems").asLong());
		assertThat(first.get("totalPages").asLong()).isEqualTo((all.get("totalItems").asLong() + 8) / 9);
		assertThat(pages).isEqualTo(vacancyIds(all).subList(0, Math.min(16, vacancyIds(all).size())));
		assertThat(beyond.get("items")).isEmpty();
		assertThat(beyond.get("totalItems").asLong()).isEqualTo(all.get("totalItems").asLong());
	}

	@Test
	@DisplayName("007-15: page o size fuera de rango o mal formados dan 422 validation-failed sin repetir el valor")
	void t007_15_invalidPaging() {
		ApiClient api = exampleStudent();

		Response sizeTooBig = api.get(COMPATIBILITY + "/recommended?size=25");
		Response sizeZero = api.get(COMPATIBILITY + "/recommended?size=0");
		Response pageZero = api.get(COMPATIBILITY + "/recommended?page=0");
		Response notANumber = api.get(COMPATIBILITY + "/recommended?size=muchas");

		assertThat(errors(sizeTooBig)).containsExactly("size:out-of-range");
		assertThat(errors(sizeZero)).containsExactly("size:out-of-range");
		assertThat(errors(pageZero)).containsExactly("page:out-of-range");
		assertThat(errors(notANumber)).containsExactly("size:invalid");
		assertThat(sizeTooBig.json().get("code").asString()).isEqualTo("validation-failed");
		assertThat(notANumber.body()).doesNotContain("muchas");
		assertPrivate(sizeTooBig);
	}

	@Test
	@DisplayName("007-15: si el perfil no tiene habilidades responde 200 con profile.sufficient falso e items vacíos")
	void t007_15_profileWithoutSkills() {
		JsonNode page = ok(student().get(COMPATIBILITY + "/recommended"));

		assertThat(page.get("profile").get("sufficient").asBoolean()).isFalse();
		assertThat(page.get("items")).isEmpty();
		assertThat(page.get("totalItems").asLong()).isZero();
		assertThat(page.get("totalPages").asInt()).isZero();
		assertThat(page.get("page").asInt()).isEqualTo(1);
		assertThat(page.get("size").asInt()).isEqualTo(9);
	}

	@Test
	@DisplayName("007-15: una vacante cerrada no se recomienda")
	void t007_15_closedNotRecommended() {
		UUID closed = insertVacancyWithJava("Cerrada recomendada 007", OffsetDateTime.now(ZoneOffset.UTC).minusHours(2),
				"CLOSED", UUID.randomUUID(), true);
		try {
			JsonNode page = ok(exampleStudent().get(COMPATIBILITY + "/recommended?size=24"));

			assertThat(vacancyIds(page)).doesNotContain(closed.toString());
		}
		finally {
			deleteVacancy(closed);
		}
	}

	// ------------------------------------------------------------------------------------------ 007-16

	@Test
	@DisplayName("007-16: summaries devuelve el resumen de cada vacante abierta pedida y omite las cerradas y las desconocidas")
	void t007_16_summaries() {
		UUID closed = insertVacancyWithJava("Cerrada summaries 007", OffsetDateTime.now(ZoneOffset.UTC).minusHours(2),
				"CLOSED", UUID.randomUUID(), true);
		try {
			ApiClient api = exampleStudent();

			Response response = api.get(COMPATIBILITY + "/summaries?vacancyId=" + BACKEND_JAVA + "&vacancyId=" + closed
					+ "&vacancyId=" + UUID.randomUUID() + "&vacancyId=d0000000-0000-4000-8000-000000000002&vacancyId="
					+ BACKEND_JAVA);

			assertThat(response.status()).isEqualTo(200);
			assertPrivate(response);
			JsonNode body = response.json();
			assertThat(body.get("sufficient").asBoolean()).isTrue();
			assertThat(elements(body.get("items"), "vacancyId")).containsExactly(BACKEND_JAVA,
					"d0000000-0000-4000-8000-000000000002");
			assertThat(body.get("items").get(0).get("percent").asInt()).isEqualTo(81);
			assertThat(body.get("items").get(0).get("level").asString()).isEqualTo("HIGH");
			assertThat(body.get("items").get(0).get("status").asString()).isEqualTo("CALCULATED");
			assertThat(body.get("items").get(0).propertyNames()).containsExactly("vacancyId", "status",
					"percent", "level", "capped");
		}
		finally {
			deleteVacancy(closed);
		}
	}

	@Test
	@DisplayName("007-16: el resumen de summaries coincide con el porcentaje del detalle y de recommended")
	void t007_16_summaryMatchesDetail() {
		ApiClient api = exampleStudent();
		String other = "d0000000-0000-4000-8000-000000000003";

		JsonNode summaries = ok(api.get(COMPATIBILITY + "/summaries?vacancyId=" + BACKEND_JAVA + "&vacancyId=" + other));
		JsonNode page = ok(api.get(COMPATIBILITY + "/recommended?size=24"));

		for (JsonNode summary : summaries.get("items")) {
			String id = summary.get("vacancyId").asString();
			assertThat(summary.get("percent")).isEqualTo(detail(api, id).get("percent"));
			assertThat(itemFor(page, id).get("compatibility")).isEqualTo(summary);
		}
	}

	@Test
	@DisplayName("007-16: con perfil sin habilidades summaries responde sufficient falso e items vacíos")
	void t007_16_profileWithoutSkills() {
		JsonNode body = ok(student().get(COMPATIBILITY + "/summaries?vacancyId=" + BACKEND_JAVA));

		assertThat(body.get("sufficient").asBoolean()).isFalse();
		assertThat(body.get("items")).isEmpty();
	}

	@Test
	@DisplayName("007-16: sin ids, con más de 24 o con ids mal formados responde 422 sin repetir el valor")
	void t007_16_invalidIds() {
		ApiClient api = exampleStudent();
		StringBuilder tooMany = new StringBuilder("?");
		for (int i = 0; i < 25; i++) {
			tooMany.append("vacancyId=").append(UUID.randomUUID()).append('&');
		}
		StringBuilder exactly24 = new StringBuilder("?");
		for (int i = 0; i < 24; i++) {
			exactly24.append("vacancyId=").append(UUID.randomUUID()).append('&');
		}

		Response none = api.get(COMPATIBILITY + "/summaries");
		Response empty = api.get(COMPATIBILITY + "/summaries?vacancyId=");
		Response malformed = api.get(COMPATIBILITY + "/summaries?vacancyId=" + BACKEND_JAVA + "&vacancyId=marca-secreta");
		Response many = api.get(COMPATIBILITY + "/summaries" + tooMany);
		Response limit = api.get(COMPATIBILITY + "/summaries" + exactly24);

		assertThat(errors(none)).containsExactly("vacancyId:required");
		assertThat(errors(empty)).containsExactly("vacancyId:required");
		assertThat(errors(malformed)).containsExactly("vacancyId:invalid");
		assertThat(malformed.body()).doesNotContain("marca-secreta");
		assertThat(errors(many)).containsExactly("vacancyId:max-items");
		assertThat(limit.status()).as("24 ids distintos son válidos").isEqualTo(200);
		assertThat(limit.json().get("items")).isEmpty();
	}

	// ------------------------------------------------------------------------------------------ 007-07 reproducible

	@Test
	@DisplayName("007-07: dos peticiones seguidas del mismo perfil dan exactamente la misma respuesta")
	void t007_07_sameDataSameAnswer() {
		ApiClient api = exampleStudent();

		JsonNode first = detail(api, BACKEND_JAVA);
		JsonNode second = detail(api, BACKEND_JAVA);
		JsonNode otherStudent = detail(exampleStudent(), BACKEND_JAVA);

		assertThat(second).isEqualTo(first);
		assertThat(otherStudent).isEqualTo(first);
	}

}
