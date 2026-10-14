package com.talenti.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.identity.Role;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/** Autorización de {@code /compatibility} (007-14): sin sesión, rol, objeto y respuestas privadas. */
class CompatibilitySecurityTests extends MatchingIntegrationTest {

	private static final List<String> ROUTES = List.of(COMPATIBILITY + "/vacancies/" + BACKEND_JAVA,
			COMPATIBILITY + "/recommended", COMPATIBILITY + "/summaries?vacancyId=" + BACKEND_JAVA);

	@Test
	@DisplayName("007-14: sin sesión las tres rutas responden 401 unauthenticated y no se guardan en caché")
	void t007_14_withoutSessionIs401() {
		for (String route : ROUTES) {
			Response response = client().get(route);

			assertThat(response.status()).as(route).isEqualTo(401);
			assertThat(response.json().get("code").asString()).isEqualTo("unauthenticated");
			assertThat(response.header("Cache-Control")).contains("no-store");
		}
	}

	@Test
	@DisplayName("007-14: con una sesión cerrada la cookie vieja da 401 session-expired")
	void t007_14_closedSessionIs401() {
		RegisteredStudent student = exampleStudentRegistered();
		String oldSession = student.api().cookie("JSESSIONID");
		student.api().post("/api/v1/auth/logout", null);
		ApiClient reuse = client();
		reuse.setCookie("JSESSIONID", oldSession);

		for (String route : ROUTES) {
			Response response = reuse.get(route);

			assertThat(response.status()).as(route).isEqualTo(401);
			assertThat(response.json().get("code").asString()).isEqualTo("session-expired");
		}
	}

	@Test
	@DisplayName("007-14: con rol COMPANY o ADMIN las tres rutas responden 403 access-denied, privado, y no calculan nada")
	void t007_14_otherRolesAreForbidden() {
		for (Role role : List.of(Role.COMPANY, Role.ADMIN)) {
			CreatedUser user = createUser(role);
			ApiClient api = client();
			assertThat(api.post("/api/v1/auth/login", loginBody(user.email(), user.password())).status()).isEqualTo(200);

			for (String route : ROUTES) {
				Response response = api.get(route);

				assertThat(response.status()).as("%s como %s", route, role).isEqualTo(403);
				assertThat(response.json().get("code").asString()).isEqualTo("access-denied");
				assertThat(response.header("Cache-Control")).contains("no-store");
				assertThat(response.body()).doesNotContain("percent");
			}
			// Tampoco una ruta inexistente de la misma familia revela algo distinto a un rol no permitido.
			assertThat(api.get(COMPATIBILITY + "/vacancies/" + UUID.randomUUID()).status()).isEqualTo(403);
		}
	}

	@Test
	@DisplayName("007-14: todas las respuestas del estudiante, de éxito y de error, llevan Cache-Control: no-store y ningún ETag")
	void t007_14_everyResponseIsNoStore() {
		ApiClient api = exampleStudent();
		List<Response> responses = List.of(api.get(ROUTES.get(0)), api.get(ROUTES.get(1)), api.get(ROUTES.get(2)),
				api.get(COMPATIBILITY + "/vacancies/" + UUID.randomUUID()), api.get(COMPATIBILITY + "/recommended?size=99"),
				api.get(COMPATIBILITY + "/summaries"));

		for (Response response : responses) {
			assertThat(response.header("Cache-Control")).contains("no-store");
			assertThat(response.header("ETag")).isNull();
			assertThat(response.header("Cache-Control")).doesNotContain("public");
		}
	}

	@Test
	@DisplayName("007-14: la persona sale de la sesión: un userId en la consulta no cambia el resultado y no existen rutas con el id de un usuario")
	void t007_14_userComesFromTheSessionOnly() {
		RegisteredStudent ana = exampleStudentRegistered();
		RegisteredStudent beto = registerStudent();
		ok(beto.api().put(PROFILE + "/skills", skills(skill("react", 3))));

		JsonNode anaOwn = detail(ana.api(), BACKEND_JAVA);
		Response betoWithAnaId = beto.api().get(COMPATIBILITY + "/vacancies/" + BACKEND_JAVA + "?userId=" + ana.id()
				+ "&user_id=" + ana.id());

		assertThat(anaOwn.get("percent").asInt()).isEqualTo(81);
		assertThat(betoWithAnaId.status()).isEqualTo(200);
		assertThat(betoWithAnaId.json().get("percent").asInt()).as("Beto ve lo suyo, no lo de Ana").isNotEqualTo(81);
		assertThat(beto.api().get(COMPATIBILITY + "/users/" + ana.id()).status()).isEqualTo(404);
		assertThat(beto.api().get(COMPATIBILITY + "/vacancies/" + BACKEND_JAVA + "/" + ana.id()).status()).isEqualTo(404);
		assertThat(beto.api().get(COMPATIBILITY + "/recommended?userId=" + ana.id()).json().get("items")).isNotEmpty();
		// Lo de Beto no alteró lo de Ana.
		assertThat(detail(ana.api(), BACKEND_JAVA)).isEqualTo(anaOwn);
	}

	@Test
	@DisplayName("007-14: las rutas son de solo lectura: un POST no calcula nada (405 con token CSRF; 403 sin él)")
	void t007_14_onlyReads() {
		ApiClient api = exampleStudent();

		Response withToken = api.post(COMPATIBILITY + "/recommended", java.util.Map.of());
		Response withoutToken = api.postWithoutCsrf(COMPATIBILITY + "/recommended", java.util.Map.of());

		assertThat(withToken.status()).isEqualTo(405);
		assertThat(withoutToken.status()).isEqualTo(403);
	}

}
