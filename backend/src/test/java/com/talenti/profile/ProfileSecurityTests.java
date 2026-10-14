package com.talenti.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.identity.Role;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/**
 * Autorización de {@code /profile} (006-04, T1): sin sesión, CSRF, rol y objeto; aislamiento entre dos estudiantes;
 * respuestas privadas.
 */
class ProfileSecurityTests extends ProfileIntegrationTest {

	private static final String SOME_PROJECT = PROFILE + "/projects/" + "c1c1c1c1-0000-4000-8000-000000000001";

	/** Una escritura válida por ruta para comprobar su seguridad: método, ruta y cuerpo. */
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
		return List.of(
				new Route("GET", PROFILE, null),
				new Route("PUT", PROFILE + "/about", body("firstNames", "Ana", "lastNames", "Pérez", "contactEmail", "ana@correo.pe", "phone", "+51 912 345 678", "about", "Una presentación de prueba.")),
				new Route("PUT", PROFILE + "/academic", body("career", "Software", "institute", "Demo", "cycle", 3)),
				new Route("PUT", PROFILE + "/skills", skills(skill("java", 1))),
				new Route("PUT", PROFILE + "/availability", body("hoursPerWeek", 10, "acceptedModalities", List.of("REMOTE"))),
				new Route("POST", PROFILE + "/projects", project("Tienda web", description(), "Java")),
				new Route("PUT", SOME_PROJECT, project("Tienda web", description(), "Java")),
				new Route("DELETE", SOME_PROJECT, null));
	}

	// ------------------------------------------------------------------------------------------ sin sesión

	@Test
	@DisplayName("006-04: sin sesión, las ocho rutas responden 401 unauthenticated")
	void t006_04_withoutSessionIs401() {
		for (Route route : routes()) {
			Response response = route.send(client());

			assertThat(response.status()).as("%s %s", route.method(), route.path()).isEqualTo(401);
			assertThat(response.json().get("code").asString()).isEqualTo("unauthenticated");
			assertThat(response.header("Cache-Control")).contains("no-store");
		}
	}

	@Test
	@DisplayName("006-04: con una sesión cerrada, la cookie vieja da 401 session-expired")
	void t006_04_closedSessionIs401() {
		RegisteredStudent student = registerStudent();
		String oldSession = student.api().cookie("JSESSIONID");
		student.api().post("/api/v1/auth/logout", null);
		ApiClient reuse = client();
		reuse.setCookie("JSESSIONID", oldSession);

		Response response = reuse.get(PROFILE);

		assertThat(response.status()).isEqualTo(401);
		assertThat(response.json().get("code").asString()).isEqualTo("session-expired");
	}

	// ------------------------------------------------------------------------------------------ CSRF

	@Test
	@DisplayName("006-04: sin token CSRF o con uno falso, las escrituras responden 403 csrf-invalid y no guardan nada")
	void t006_04_writesNeedCsrf() {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		String forged = "no-es-el-token";

		for (Route route : routes()) {
			if (route.method().equals("GET")) {
				continue;
			}
			Response withoutToken = api.writeWithToken(route.method(), route.path(), route.body(), null);
			Response forgedToken = api.writeWithToken(route.method(), route.path(), route.body(), forged);

			assertThat(withoutToken.status()).as("%s %s sin token", route.method(), route.path()).isEqualTo(403);
			assertThat(withoutToken.json().get("code").asString()).isEqualTo("csrf-invalid");
			assertThat(forgedToken.status()).as("%s %s token falso", route.method(), route.path()).isEqualTo(403);
		}
		assertThat(profileRows(student.id())).isZero();
	}

	// ------------------------------------------------------------------------------------------ rol

	@Test
	@DisplayName("006-04: con rol COMPANY o ADMIN, las ocho rutas responden 403 access-denied y no crean nada")
	void t006_04_otherRolesAreForbidden() {
		for (Role role : List.of(Role.COMPANY, Role.ADMIN)) {
			CreatedUser user = createUser(role);
			ApiClient api = client();
			assertThat(api.post("/api/v1/auth/login", loginBody(user.email(), user.password())).status()).isEqualTo(200);

			for (Route route : routes()) {
				Response response = route.send(api);

				assertThat(response.status()).as("%s %s como %s", route.method(), route.path(), role).isEqualTo(403);
				assertThat(response.json().get("code").asString()).isEqualTo("access-denied");
				assertThat(response.header("Cache-Control")).contains("no-store");
			}
			assertThat(profileRows(user.id())).as("%s no crea perfil", role).isZero();
			assertThat(count("SELECT count(*) FROM student_skill WHERE user_id = ?", user.id())).isZero();
			assertThat(count("SELECT count(*) FROM student_project WHERE user_id = ?", user.id())).isZero();
		}
	}

	// ------------------------------------------------------------------------------------------ objeto

	@Test
	@DisplayName("006-04: dos estudiantes nunca ven datos del otro: cada GET devuelve solo lo propio")
	void t006_04_studentsAreIsolated() {
		ApiClient ana = student();
		ApiClient beto = student();
		ok(ana.put(PROFILE + "/academic", body("career", "Carrera de Ana", "institute", "Instituto A", "cycle", 2)));
		ok(ana.put(PROFILE + "/skills", skills(skill("java", 3))));
		ok(ana.post(PROFILE + "/projects", project("Proyecto de Ana", description(), "Java")));
		ok(beto.put(PROFILE + "/academic", body("career", "Carrera de Beto", "institute", "Instituto B", "cycle", 6)));

		JsonNode anaProfile = ana.get(PROFILE).json();
		JsonNode betoProfile = beto.get(PROFILE).json();

		assertThat(anaProfile.get("academic").get("career").asString()).isEqualTo("Carrera de Ana");
		assertThat(betoProfile.get("academic").get("career").asString()).isEqualTo("Carrera de Beto");
		assertThat(betoProfile.get("skills")).isEmpty();
		assertThat(betoProfile.get("projects")).isEmpty();
		assertThat(betoProfile.toString()).doesNotContain("Ana");
		// Escribir en uno no cambia al otro.
		ok(beto.put(PROFILE + "/skills", skills(skill("sql", 1))));
		assertThat(ana.get(PROFILE).json().get("skills")).hasSize(1);
		assertThat(ana.get(PROFILE).json().get("skills").get(0).get("slug").asString()).isEqualTo("java");
	}

	@Test
	@DisplayName("006-04: el proyecto de otra persona da 404 project-not-found, igual que uno inexistente, y no se modifica")
	void t006_04_foreignProjectIsNotFound() {
		ApiClient ana = student();
		ApiClient beto = student();
		JsonNode created = ok(ana.post(PROFILE + "/projects", project("Proyecto de Ana", description(), "Java")));
		String anaProjectId = created.get("projects").get(0).get("id").asString();

		Response foreignPut = beto.put(PROFILE + "/projects/" + anaProjectId, project("Robado", description(), "Java"));
		Response foreignDelete = beto.delete(PROFILE + "/projects/" + anaProjectId);
		Response missingPut = beto.put(PROFILE + "/projects/" + UUID.randomUUID(), project("Robado", description(), "Java"));
		Response missingDelete = beto.delete(PROFILE + "/projects/" + UUID.randomUUID());
		Response malformed = beto.delete(PROFILE + "/projects/abc");

		for (Response response : List.of(foreignPut, foreignDelete, missingPut, missingDelete, malformed)) {
			assertThat(response.status()).isEqualTo(404);
			assertThat(response.json().get("code").asString()).isEqualTo("project-not-found");
		}
		// El mensaje es idéntico: no se puede distinguir un proyecto ajeno de uno inexistente.
		assertThat(foreignPut.json().get("detail")).isEqualTo(missingPut.json().get("detail"));
		assertThat(foreignDelete.json().get("detail")).isEqualTo(missingDelete.json().get("detail"));
		JsonNode anaAfter = ana.get(PROFILE).json();
		assertThat(anaAfter.get("projects")).hasSize(1);
		assertThat(anaAfter.get("projects").get(0).get("name").asString()).isEqualTo("Proyecto de Ana");
	}

	@Test
	@DisplayName("006-04: la persona sale de la sesión: un userId en la ruta, la consulta o el cuerpo no cambia a quién se guarda")
	void t006_04_userComesFromTheSessionOnly() {
		RegisteredStudent ana = registerStudent();
		RegisteredStudent beto = registerStudent();

		ok(beto.api().put(PROFILE + "/academic?userId=" + ana.id(),
				body("career", "Carrera de Beto", "userId", ana.id().toString(), "user_id", ana.id().toString())));

		assertThat(ana.api().get(PROFILE).json().get("academic").get("career").isNull()).isTrue();
		assertThat(profileRows(ana.id())).isZero();
		assertThat(profileRows(beto.id())).isEqualTo(1);
		// No existen rutas con el id de un usuario.
		assertThat(beto.api().get(PROFILE + "/" + ana.id()).status()).isIn(404, 405);
		assertThat(beto.api().get("/api/v1/users/" + ana.id() + "/profile").status()).isEqualTo(404);
	}

	// ------------------------------------------------------------------------------------------ privado

	@Test
	@DisplayName("006-04: todas las respuestas, también las de error, llevan Cache-Control: no-store y ningún ETag")
	void t006_04_responsesAreNoStore() {
		ApiClient api = student();

		List<Response> responses = List.of(
				api.get(PROFILE),
				api.put(PROFILE + "/academic", body("career", "Software")),
				api.put(PROFILE + "/academic", body("cycle", 99)),
				api.put(PROFILE + "/skills", skills(skill("cobol", 1))),
				api.post(PROFILE + "/projects", project("Tienda web", description(), "Java")),
				api.delete(PROFILE + "/projects/" + UUID.randomUUID()),
				api.put(PROFILE + "/projects/otra-cosa", project("Tienda web", description(), "Java")));

		for (Response response : responses) {
			assertThat(response.header("Cache-Control")).as("estado %d", response.status()).contains("no-store");
			assertThat(response.header("ETag")).isNull();
		}
		assertThat(responses).extracting(Response::status).containsExactly(200, 200, 422, 422, 201, 404, 404);
	}

	@Test
	@DisplayName("006-04: un cuerpo desmedido (más de 64 KB) se rechaza con 413 sin guardarlo")
	void t006_04_oversizedBodyIsRejected() {
		RegisteredStudent student = registerStudent();

		Response response = student.api().put(PROFILE + "/academic", body("career", repeat('a', 70_000)));

		assertThat(response.status()).isEqualTo(413);
		assertThat(response.json().get("code").asString()).isEqualTo("payload-too-large");
		assertThat(profileRows(student.id())).isZero();
	}

	@Test
	@DisplayName("006-04: el tope de 64 KB también vale con cuerpo chunked (sin Content-Length): 413 y no se guarda")
	void t006_04_chunkedOversizedBodyIsRejected() {
		RegisteredStudent student = registerStudent();
		byte[] huge = ("{\"career\":\"" + repeat('a', 200_000) + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);

		Response response = student.api().writeChunked("PUT", PROFILE + "/academic", huge);

		assertThat(response.status()).isEqualTo(413);
		assertThat(response.json().get("code").asString()).isEqualTo("payload-too-large");
		assertThat(profileRows(student.id())).isZero();
		// Un cuerpo chunked pequeño sí pasa.
		byte[] small = "{\"career\":\"Software\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(student.api().writeChunked("PUT", PROFILE + "/academic", small).status()).isEqualTo(200);
	}

	@Test
	@DisplayName("006-04: un método no admitido da 405 y no destapa trazas ni datos")
	void t006_04_otherErrorsAreProblemDetails() {
		ApiClient api = student();

		Response wrongMethod = api.delete(PROFILE + "/academic");

		assertThat(wrongMethod.status()).isEqualTo(405);
		assertThat(wrongMethod.header("Cache-Control")).contains("no-store");
		assertThat(wrongMethod.body()).doesNotContain("Exception").doesNotContain("at com.");
	}

}
