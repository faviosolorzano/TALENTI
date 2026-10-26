package com.talenti.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.identity.Role;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

/** Seguridad de {@code /github} (013-23): sesión, CSRF, rol, respuestas privadas y límite por persona. */
class GithubSecurityTests extends GithubIntegrationTest {

	private record Route(String method, String path, Object body) {

		Response send(ApiClient api) {
			return switch (method) {
				case "GET" -> api.get(path);
				case "PUT" -> api.put(path, body);
				case "POST" -> api.post(path, body);
				default -> api.delete(path);
			};
		}

	}

	private static List<Route> routes() {
		return List.of(new Route("GET", GITHUB, null), new Route("POST", GITHUB + "/oauth/start", null),
				new Route("GET", GITHUB + "/repositories", null),
				new Route("PUT", GITHUB + "/repositories/selection", body("repoIds", List.of())),
				new Route("POST", GITHUB + "/refresh", null), new Route("GET", GITHUB + "/suggestions", null),
				new Route("POST", GITHUB + "/suggestions/decisions", body("items", List.of())),
				new Route("DELETE", GITHUB, null));
	}

	@Test
	@DisplayName("013-23: sin sesión, todas las rutas (menos la vuelta) responden 401")
	void t013_23_withoutSessionIs401() {
		for (Route route : routes()) {
			Response response = route.send(client());

			assertThat(response.status()).as("%s %s", route.method(), route.path()).isEqualTo(401);
			assertThat(response.json().get("code").asString()).isEqualTo("unauthenticated");
		}
	}

	@Test
	@DisplayName("013-23: las escrituras sin token CSRF responden 403 csrf-invalid")
	void t013_23_writesWithoutCsrfAre403() {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		for (Route route : routes()) {
			if (route.method().equals("GET")) {
				continue;
			}
			Response response = api.writeWithToken(route.method(), route.path(), route.body(), null);

			assertThat(response.status()).as("%s %s", route.method(), route.path()).isEqualTo(403);
			assertThat(response.json().get("code").asString()).isEqualTo("csrf-invalid");
		}
		assertThat(count("SELECT count(*) FROM github_oauth_state WHERE user_id = ?", student.id())).isZero();
	}

	@Test
	@DisplayName("013-23: un rol distinto de estudiante recibe 403 access-denied en todas las rutas; la vuelta lo redirige con invalid-state")
	void t013_23_otherRolesAre403() {
		CreatedUser company = createUser(Role.COMPANY);
		ApiClient api = client();
		assertThat(api.post("/api/v1/auth/login", loginBody(company.email(), company.password())).status()).isEqualTo(200);

		for (Route route : routes()) {
			Response response = route.send(api);

			assertThat(response.status()).as("%s %s", route.method(), route.path()).isEqualTo(403);
			assertThat(response.json().get("code").asString()).isEqualTo("access-denied");
			assertThat(response.header("Cache-Control")).contains("no-store");
		}
		assertThat(outcome(api.get(GITHUB + "/oauth/callback?code=a&state=b"))).isEqualTo("invalid-state");
	}

	@Test
	@DisplayName("013-23: las respuestas son privadas (Cache-Control: no-store)")
	void t013_23_responsesAreNoStore() {
		Linked linked = linkNew("privado");

		for (Response response : List.of(linked.api().get(GITHUB), linked.api().get(GITHUB + "/repositories"),
				linked.api().get(GITHUB + "/suggestions"), linked.api().post(GITHUB + "/oauth/start", null))) {
			assertThat(response.header("Cache-Control")).contains("no-store");
		}
	}

	@Test
	@DisplayName("013-23: más de 30 peticiones por minuto de una persona en /github responden 429 con Retry-After")
	void t013_23_rateLimitPerPerson() {
		ApiClient api = student();
		for (int i = 0; i < 30; i++) {
			assertThat(api.get(GITHUB).status()).isEqualTo(204);
		}

		Response limited = api.get(GITHUB);

		assertThat(limited.status()).isEqualTo(429);
		assertThat(limited.header("Retry-After")).isNotNull();
		// Otra persona no se ve afectada.
		assertThat(student().get(GITHUB).status()).isEqualTo(204);
	}

}
