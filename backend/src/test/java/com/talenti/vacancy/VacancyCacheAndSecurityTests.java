package com.talenti.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

/** Caché y ETag de las rutas públicas (004-05) y sus límites de seguridad (004-14). */
class VacancyCacheAndSecurityTests extends VacancyIntegrationTest {

	private static final String CACHE = "public, max-age=60";

	/** Las cinco rutas de lectura. */
	private List<String> readRoutes() {
		return List.of("/api/v1/vacancies", "/api/v1/vacancies/" + BACKEND_JAVA, "/api/v1/vacancies/featured",
				"/api/v1/vacancies/filters", "/api/v1/companies/featured");
	}

	// ------------------------------------------------------------------------------------------ 004-05

	@Test
	@DisplayName("004-05: las cinco rutas de lectura responden 'public, max-age=60' (no no-store) y ETag")
	void t004_05_cacheControlAndEtag() {
		for (String route : readRoutes()) {
			Response response = client().get(route);

			assertThat(response.status()).as(route).isEqualTo(200);
			assertThat(response.header("Cache-Control")).as(route).isEqualTo(CACHE);
			assertThat(response.header("Cache-Control")).doesNotContain("no-store");
			assertThat(response.header("ETag")).as(route).isNotBlank().startsWith("\"");
			assertThat(response.header("Pragma")).as(route + " sin Pragma: no-cache").isNull();
			assertThat(response.header("Expires")).as(route + " sin Expires: 0").isNull();
			assertThat(response.setCookies()).as(route).isEmpty();
		}
	}

	@Test
	@DisplayName("004-05: con If-None-Match vigente responden 304 sin cuerpo; con uno viejo, 200")
	void t004_05_conditionalRequests() {
		for (String route : readRoutes()) {
			String etag = client().get(route).header("ETag");

			Response notModified = client().get(route, Map.of("If-None-Match", etag));

			assertThat(notModified.status()).as(route).isEqualTo(304);
			assertThat(notModified.body()).as(route).isEmpty();
			assertThat(notModified.header("ETag")).as(route).isEqualTo(etag);
			assertThat(notModified.header("Cache-Control")).as(route).isEqualTo(CACHE);
			assertThat(notModified.setCookies()).as(route).isEmpty();

			Response stale = client().get(route, Map.of("If-None-Match", "\"viejo\""));
			assertThat(stale.status()).as(route).isEqualTo(200);
			assertThat(stale.body()).as(route).isNotEmpty();
		}
	}

	@Test
	@DisplayName("004-05: cada representación tiene su ETag (otra página o filtro cambia el ETag) y es estable")
	void t004_05_etagFollowsTheContent() {
		String page1 = client().get("/api/v1/vacancies").header("ETag");
		String page2 = client().get("/api/v1/vacancies?page=2").header("ETag");
		String filtered = client().get("/api/v1/vacancies?modality=REMOTE").header("ETag");

		assertThat(page1).isEqualTo(client().get("/api/v1/vacancies").header("ETag"));
		assertThat(List.of(page1, page2, filtered)).doesNotHaveDuplicates();
		assertThat(client().get("/api/v1/vacancies?page=2", Map.of("If-None-Match", page1)).status()).isEqualTo(200);
	}

	@Test
	@DisplayName("004-05: el ajuste de caché es solo para esas rutas: el resto sigue con no-store y los errores también")
	void t004_05_restOfTheApiKeepsNoStore() {
		ApiClient visitor = client();
		Response csrf = visitor.get("/api/v1/auth/csrf");
		Response legal = client().get("/api/v1/legal/current");
		Response unauthenticated = client().get("/api/v1/me");
		Response notFound = client().get("/api/v1/vacancies/" + "99999999-9999-4999-8999-999999999999");
		Response invalid = client().get("/api/v1/vacancies?size=0");

		for (Response response : List.of(csrf, legal, unauthenticated, notFound, invalid)) {
			assertThat(response.header("Cache-Control")).contains("no-store");
			assertThat(response.header("ETag")).isNull();
		}
		assertThat(legal.status()).isEqualTo(200);
		assertThat(unauthenticated.status()).isEqualTo(401);
	}

	// ------------------------------------------------------------------------------------------ 004-04 / 004-14

	@Test
	@DisplayName("004-04: ninguna de las rutas públicas (ni sus errores) devuelve Set-Cookie ni crea sesión")
	void t004_04_noCookiesAnywhere() {
		ApiClient visitor = client();
		List<String> routes = List.of("/api/v1/vacancies", "/api/v1/vacancies?q=java&modality=REMOTE",
				"/api/v1/vacancies/" + BACKEND_JAVA, "/api/v1/vacancies/featured", "/api/v1/vacancies/filters",
				"/api/v1/companies/featured", "/api/v1/vacancies/no-existe", "/api/v1/vacancies?size=99",
				"/api/v1/vacancies/featured?limit=99");

		for (String route : routes) {
			Response response = visitor.get(route);
			assertThat(response.setCookies()).as(route).isEmpty();
		}
		assertThat(visitor.cookies()).isEmpty();
	}

	@Test
	@DisplayName("004-14: POST, PUT y DELETE sin sesión sobre las rutas de vacantes reciben 401")
	void t004_14_writesWithoutSessionAre401() {
		for (String route : List.of("/api/v1/vacancies", "/api/v1/vacancies/" + BACKEND_JAVA,
				"/api/v1/vacancies/featured", "/api/v1/vacancies/filters", "/api/v1/companies/featured")) {
			// Con token CSRF válido, para que la negativa venga de la falta de sesión y no del CSRF.
			ApiClient visitor = client();
			assertThat(visitor.post(route, Map.of("title", "x")).status()).as("POST " + route).isEqualTo(401);
			assertThat(visitor.put(route, Map.of("title", "x")).status()).as("PUT " + route).isEqualTo(401);
			assertThat(visitor.delete(route).status()).as("DELETE " + route).isEqualTo(401);
			Response post = visitor.post(route, Map.of("title", "x"));
			assertThat(post.json().get("code").asString()).isEqualTo("unauthenticated");
			// Sin token CSRF, ni siquiera llega: la protección CSRF sigue activa.
			assertThat(client().postWithoutCsrf(route, Map.of("title", "x")).status()).as("POST sin CSRF " + route)
					.isEqualTo(403);
		}
		assertThat(count("SELECT count(*) FROM vacancy WHERE title = 'x'")).isZero();
	}

	@Test
	@DisplayName("004-14: cualquier otra ruta de vacancy sigue exigiendo sesión (001-13); GET /skills es público desde 006-02, no sus subrutas")
	void t004_14_otherRoutesRequireSession() {
		for (String route : List.of("/api/v1/vacancies/featured/otra", "/api/v1/vacancies/" + BACKEND_JAVA + "/requirements",
				"/api/v1/vacancies/" + BACKEND_JAVA + "/applications", "/api/v1/companies",
				"/api/v1/companies/" + ANDES_TECH, "/api/v1/skills/java")) {
			Response response = client().get(route);

			assertThat(response.status()).as(route).isEqualTo(401);
			assertThat(response.json().get("code").asString()).isEqualTo("unauthenticated");
		}
	}

}
