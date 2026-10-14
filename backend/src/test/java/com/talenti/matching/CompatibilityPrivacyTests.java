package com.talenti.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.SpringBootTest;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/**
 * Privacidad y límites del cálculo: lo personal no entra (007-10), calcular no escribe nada (007, sección 4: sin
 * tablas nuevas, sin consumo de IA) y los registros no llevan nada del perfil. Se sube el nivel de registro al máximo
 * en las partes que tocan estas rutas para que la prueba sea exigente (sin TRACE de {@code org.springframework.jdbc},
 * donde Spring imprime los parámetros SQL y que ningún perfil de Talenti enciende).
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"talenti.demo-data.enabled=true",
		"logging.level.com.talenti=TRACE",
		"logging.level.org.springframework.security=TRACE",
		"logging.level.org.springframework.web=TRACE",
		"logging.level.org.springframework.jdbc=DEBUG",
		"logging.level.org.springframework.transaction=TRACE",
		"spring.mvc.log-request-details=true" })
class CompatibilityPrivacyTests extends MatchingIntegrationTest {

	@Test
	@DisplayName("007-10: cambiar el correo, el instituto, la carrera, el ciclo, el nombre, la presentación o los proyectos no cambia el resultado")
	void t007_10_personalDataDoesNotChangeTheResult() {
		RegisteredStudent student = exampleStudentRegistered();
		ApiClient api = student.api();
		JsonNode before = detail(api, BACKEND_JAVA);
		JsonNode beforeList = ok(api.get(COMPATIBILITY + "/recommended?size=24"));

		ok(api.put(PROFILE + "/academic", body("career", "Carrera totalmente distinta", "institute", "Otro instituto",
				"cycle", 9)));
		ok(api.put(PROFILE + "/about", body("displayName", "Otro Nombre", "about", "Una presentación muy distinta.")));
		ok(api.post(PROFILE + "/projects", project("Proyecto con Java y Spring Boot", description(), "Java", "Spring")));
		jdbc.update("UPDATE app_user SET email = ? WHERE id = ?", "otro." + UUID.randomUUID() + "@ejemplo.test",
				student.id());

		assertThat(detail(api, BACKEND_JAVA)).isEqualTo(before);
		assertThat(ok(api.get(COMPATIBILITY + "/recommended?size=24"))).isEqualTo(beforeList);
	}

	@Test
	@DisplayName("007-10: lo que sí cuenta cambia el resultado (nivel de una habilidad, horas), así que la prueba anterior no es vacía")
	void t007_10_relevantDataDoesChangeTheResult() {
		ApiClient api = exampleStudent();
		JsonNode before = detail(api, BACKEND_JAVA);

		ok(api.put(PROFILE + "/skills", skills(skill("java", 2), skill("spring-boot", 1), skill("sql", 2), skill("git", 1))));
		JsonNode afterSkill = detail(api, BACKEND_JAVA);
		ok(api.put(PROFILE + "/availability",
				body("hoursPerWeek", 10, "acceptedModalities", List.of("REMOTE"), "location", "Lima")));
		JsonNode afterHours = detail(api, BACKEND_JAVA);

		assertThat(before.get("percent").asInt()).isEqualTo(81);
		assertThat(afterSkill.get("percent").asInt()).isGreaterThan(81);
		assertThat(afterHours.get("percent").asInt()).isLessThan(afterSkill.get("percent").asInt());
	}

	@Test
	@DisplayName("007 (sección 4): calcular, listar y abrir un detalle no escribe en ninguna tabla ni crea filas nuevas")
	void t007_calculatingWritesNothing() {
		ApiClient api = exampleStudent();
		Map<String, Integer> before = rowCounts();

		detail(api, BACKEND_JAVA);
		ok(api.get(COMPATIBILITY + "/recommended"));
		ok(api.get(COMPATIBILITY + "/summaries?vacancyId=" + BACKEND_JAVA));
		api.get(COMPATIBILITY + "/vacancies/" + UUID.randomUUID());

		assertThat(rowCounts()).isEqualTo(before);
		assertThat(before).as("no hay tablas de resultados ni de embeddings").doesNotContainKeys("match_result",
				"profile_embedding", "vacancy_embedding");
		// ai_usage_event existe desde la spec 009; la igualdad de conteos de arriba prueba que la compatibilidad no escribe en ella (009-33).
		assertThat(before).containsKey("ai_usage_event");
	}

	private Map<String, Integer> rowCounts() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (String table : jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY 1",
				String.class)) {
			counts.put(table, count("SELECT count(*) FROM \"" + table + "\""));
		}
		return counts;
	}

	@Test
	@DisplayName("007: ni las habilidades, ni la ubicación, ni los proyectos, ni el correo del perfil aparecen en los registros")
	void t007_profileContentIsNeverLogged(CapturedOutput output) {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		String mark = "marca" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);
		// Valor inválido que manda el navegador por la URL: la respuesta 422 no debe repetirlo (el registro de
		// direcciones del servidor web no es del perfil y no se revisa aquí).
		String echo = "eco" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
		ok(api.put(PROFILE + "/skills", skills(skill("java", 2), skill("sql", 1))));
		ok(api.put(PROFILE + "/availability",
				body("hoursPerWeek", 12, "acceptedModalities", List.of("HYBRID"), "location", "Ciudad " + mark)));
		ok(api.post(PROFILE + "/projects", project("Proyecto " + mark, "Descripcion " + mark, "Tec" + mark.substring(0, 10))));
		ok(api.put(PROFILE + "/academic", body("career", "Carrera " + mark, "institute", "Instituto " + mark, "cycle", 4)));

		List<Response> responses = List.of(api.get(COMPATIBILITY + "/vacancies/" + BACKEND_JAVA),
				api.get(COMPATIBILITY + "/vacancies/d0000000-0000-4000-8000-000000000002"),
				api.get(COMPATIBILITY + "/recommended?size=24"),
				api.get(COMPATIBILITY + "/summaries?vacancyId=" + BACKEND_JAVA + "&vacancyId=" + echo),
				api.get(COMPATIBILITY + "/recommended?size=" + echo));

		assertThat(responses).extracting(Response::status).containsExactly(200, 200, 200, 422, 422);
		assertThat(responses.get(3).body()).doesNotContain(echo);
		assertThat(responses.get(4).body()).doesNotContain(echo);
		assertThat(output.getAll()).as("registros de la aplicación").isNotEmpty()
				.doesNotContain(mark)
				.doesNotContain(student.email())
				.doesNotContain(student.password());
	}

}
