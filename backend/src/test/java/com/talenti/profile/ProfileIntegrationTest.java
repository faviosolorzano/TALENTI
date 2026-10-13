package com.talenti.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.talenti.identity.IdentityIntegrationTest;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/**
 * Base de las pruebas de integración del perfil (spec 006): aplicación completa con servidor HTTP real y PostgreSQL
 * real. Reutiliza los ayudantes de identity (registrar estudiantes, crear otros roles). Todos los datos son
 * inventados.
 */
public abstract class ProfileIntegrationTest extends IdentityIntegrationTest {

	protected static final String PROFILE = "/api/v1/profile";

	/** Mapa con valores nulos permitidos, en el orden dado: {@code body("career", "x", "cycle", null)}. */
	protected static Map<String, Object> body(Object... keysAndValues) {
		Map<String, Object> map = new LinkedHashMap<>();
		for (int i = 0; i < keysAndValues.length; i += 2) {
			map.put((String) keysAndValues[i], keysAndValues[i + 1]);
		}
		return map;
	}

	protected static Map<String, Object> skill(String slug, Object level) {
		return body("slug", slug, "level", level);
	}

	@SafeVarargs
	protected static Map<String, Object> skills(Map<String, Object>... items) {
		return body("skills", List.of(items));
	}

	protected static Map<String, Object> project(String name, String description, String... technologies) {
		return body("name", name, "description", description, "technologies", List.of(technologies));
	}

	protected static String repeat(char c, int times) {
		return String.valueOf(c).repeat(times);
	}

	/** Una descripción válida de proyecto (10 a 500 caracteres). */
	protected static String description() {
		return "Aplicación de prueba con datos inventados.";
	}

	/** Un estudiante nuevo con la sesión abierta. */
	protected ApiClient student() {
		return registerStudent().api();
	}

	protected static JsonNode ok(Response response) {
		assertThat(response.status()).as("respuesta: %s", response.body()).isIn(200, 201);
		return response.json();
	}

	/** Los pares {@code campo:regla} del arreglo {@code errors} de un 422. */
	protected static List<String> errors(Response response) {
		assertThat(response.status()).isEqualTo(422);
		List<String> result = new ArrayList<>();
		for (JsonNode error : response.json().get("errors")) {
			result.add(error.get("field").asString() + ":" + error.get("rule").asString());
		}
		return result;
	}

	/** Deja un perfil con las cinco partes completas (100 %). */
	protected JsonNode fillEverything(ApiClient api) {
		ok(api.put(PROFILE + "/academic", body("career", "Desarrollo de Software", "institute", "Instituto Demo", "cycle", 5)));
		ok(api.put(PROFILE + "/skills", skills(skill("java", 2), skill("sql", 1), skill("git", 3))));
		ok(api.put(PROFILE + "/availability", body("hoursPerWeek", 20, "acceptedModalities", List.of("REMOTE", "HYBRID"),
				"location", "Lima")));
		return ok(api.post(PROFILE + "/projects", project("Tienda web", description(), "Java", "PostgreSQL")));
	}

	protected int profileRows(java.util.UUID userId) {
		return count("SELECT count(*) FROM student_profile WHERE user_id = ?", userId);
	}

}
