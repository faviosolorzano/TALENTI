package com.talenti.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.test.context.SpringBootTest;

import com.talenti.profile.ProfileIntegrationTest;
import com.talenti.support.ApiClient;

import tools.jackson.databind.JsonNode;

/**
 * Base de las pruebas de integración de la compatibilidad (spec 007): aplicación completa con servidor HTTP real,
 * PostgreSQL real, los datos de ejemplo cargados (12 vacantes abiertas) y los ayudantes de perfil de la spec 006.
 * Todos los datos son inventados. Las pruebas que agregan vacantes las borran al terminar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "talenti.demo-data.enabled=true")
public abstract class MatchingIntegrationTest extends ProfileIntegrationTest {

	protected static final String COMPATIBILITY = "/api/v1/compatibility";

	/** "Practicante Backend Java": los requisitos del ejemplo de RN-01 (remota, 20 horas). */
	protected static final String BACKEND_JAVA = "d0000000-0000-4000-8000-000000000001";

	protected static final String NEXA_DIGITAL = "c0000000-0000-4000-8000-000000000001";

	/** Un estudiante con el perfil del ejemplo de RN-01: Java 2, Spring Boot 1, SQL 1, Git 1; remoto; 20 horas. */
	protected ApiClient exampleStudent() {
		return exampleStudentRegistered().api();
	}

	protected RegisteredStudent exampleStudentRegistered() {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		ok(api.put(PROFILE + "/skills",
				skills(skill("java", 2), skill("spring-boot", 1), skill("sql", 1), skill("git", 1))));
		ok(api.put(PROFILE + "/availability",
				body("hoursPerWeek", 20, "acceptedModalities", List.of("REMOTE"), "location", "Lima")));
		return student;
	}

	protected JsonNode detail(ApiClient api, String vacancyId) {
		return ok(api.get(COMPATIBILITY + "/vacancies/" + vacancyId));
	}

	/** Inserta una vacante abierta (presencial en Lima, 10 horas) con un requisito obligatorio de Java nivel 1. */
	protected UUID insertVacancyWithJava(String title, OffsetDateTime publishedAt, String status, UUID id,
			boolean withRequirement) {
		jdbc.update("""
				INSERT INTO vacancy (id, company_id, title, summary, description, modality, location, hours_per_week,
				                     published_at, status, featured)
				VALUES (?, ?::uuid, ?, 'Resumen de prueba.', 'Vacante de prueba.', 'ONSITE', 'Lima', 10, ?, ?, false)""",
				id, NEXA_DIGITAL, title, publishedAt, status);
		if (withRequirement) {
			jdbc.update("""
					INSERT INTO vacancy_requirement (vacancy_id, skill_id, kind, min_level)
					SELECT ?, id, 'REQUIRED', 1 FROM skill WHERE slug = 'java'""", id);
		}
		return id;
	}

	protected UUID insertOpenWithJava(String title) {
		return insertVacancyWithJava(title, OffsetDateTime.now(ZoneOffset.UTC).minusHours(1), "OPEN", UUID.randomUUID(),
				true);
	}

	protected void deleteVacancy(UUID id) {
		jdbc.update("DELETE FROM vacancy WHERE id = ?", id);
	}

	protected static List<String> vacancyIds(JsonNode page) {
		List<String> ids = new ArrayList<>();
		for (JsonNode item : page.get("items")) {
			ids.add(item.get("vacancy").get("id").asString());
		}
		return ids;
	}

	protected static JsonNode itemFor(JsonNode page, String vacancyId) {
		for (JsonNode item : page.get("items")) {
			if (item.get("vacancy").get("id").asString().equals(vacancyId)) {
				return item;
			}
		}
		throw new AssertionError("La vacante " + vacancyId + " no está en la página");
	}

	protected static void assertPrivate(ApiClient.Response response) {
		assertThat(response.header("Cache-Control")).contains("no-store");
	}

}
