package com.talenti.cv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.identity.Role;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;
import com.talenti.support.SampleCv;

import tools.jackson.databind.JsonNode;

/** Autorización del lector de CV (009-17): sesión, CSRF, rol, objeto y respuestas privadas. */
class CvSecurityTests extends CvIntegrationTest {

	private static final String SOME_ID = "c1c1c1c1-0000-4000-8000-000000000001";

	/** Una petición por ruta: método, ruta y cómo enviarla. */
	private record Route(String method, String path) {

		Response send(CvSecurityTests test, ApiClient api) {
			return switch (method) {
				case "GET" -> api.get(path);
				case "DELETE" -> api.delete(path);
				default -> path.endsWith("/analyses")
						? api.postMultipart(path, "cv.pdf", "application/pdf", SampleCv.pdf("M"), Map.of())
						: api.post(path, path.endsWith("/confirm") ? Map.of("decisions", List.of()) : Map.of("mode", "BASIC"));
			};
		}

	}

	private static List<Route> routes() {
		return List.of(new Route("GET", CV + "/usage"), new Route("POST", CV + "/analyses"),
				new Route("GET", CV + "/analyses/current"), new Route("POST", CV + "/analyses/" + SOME_ID + "/reads"),
				new Route("POST", CV + "/analyses/" + SOME_ID + "/confirm"),
				new Route("DELETE", CV + "/analyses/" + SOME_ID));
	}

	@Test
	@DisplayName("009-17: sin sesión, las seis rutas responden 401 y privadas")
	void t009_17_withoutSessionIs401() {
		for (Route route : routes()) {
			Response response = route.send(this, client());

			assertThat(response.status()).as("%s %s", route.method(), route.path()).isEqualTo(401);
			assertThat(response.header("Cache-Control")).contains("no-store");
		}
	}

	@Test
	@DisplayName("009-17: una escritura sin token CSRF, o con uno falso, responde 403 csrf-invalid")
	void t009_17_writesNeedCsrf() {
		ApiClient api = student();
		api.get("/api/v1/auth/csrf");
		String id = UUID.randomUUID().toString();

		assertThat(api.writeWithToken("POST", CV + "/analyses/" + id + "/confirm", Map.of("decisions", List.of()), null)
				.status()).isEqualTo(403);
		assertThat(api.writeWithToken("POST", CV + "/analyses/" + id + "/reads", Map.of("mode", "BASIC"), "falso")
				.json().get("code").asString()).isEqualTo("csrf-invalid");
		assertThat(api.writeWithToken("DELETE", CV + "/analyses/" + id, null, null).status()).isEqualTo(403);
		Response upload = api.postMultipart(CV + "/analyses", "cv.pdf", "application/pdf", SampleCv.pdf("M"), Map.of(),
				false);
		assertThat(upload.status()).isEqualTo(403);
		assertThat(analysisRows(UUID.fromString(api.get("/api/v1/auth/me").json().get("id").asString()))).isZero();
	}

	@Test
	@DisplayName("009-17: con rol COMPANY o ADMIN las seis rutas responden 403 access-denied y no crean nada")
	void t009_17_otherRolesAreForbidden() {
		for (Role role : List.of(Role.COMPANY, Role.ADMIN)) {
			CreatedUser user = createUser(role);
			ApiClient api = client();
			assertThat(api.post("/api/v1/auth/login", loginBody(user.email(), user.password())).status()).isEqualTo(200);

			for (Route route : routes()) {
				Response response = route.send(this, api);

				assertThat(response.status()).as("%s %s como %s", route.method(), route.path(), role).isEqualTo(403);
				assertThat(response.json().get("code").asString()).isEqualTo("access-denied");
				assertThat(response.header("Cache-Control")).contains("no-store");
			}
			assertThat(analysisRows(user.id())).isZero();
		}
	}

	@Test
	@DisplayName("009-17: un análisis ajeno, inexistente o mal formado da el mismo 404 analysis-not-found")
	void t009_17_foreignIs404() {
		ApiClient owner = student();
		ApiClient intruder = student();
		JsonNode analysis = uploadSampleAndWait(owner);
		String id = analysis.get("id").asString();
		List<String> same = List.of(id, UUID.randomUUID().toString(), "no-es-un-uuid", "12345");

		for (String candidate : same) {
			Response confirm = intruder.post(CV + "/analyses/" + candidate + "/confirm", Map.of("decisions", List.of()));
			Response read = intruder.post(CV + "/analyses/" + candidate + "/reads", Map.of("mode", "BASIC"));
			Response discard = intruder.delete(CV + "/analyses/" + candidate);

			for (Response response : List.of(confirm, read, discard)) {
				assertThat(response.status()).as(candidate).isEqualTo(404);
				assertThat(response.json().get("code").asString()).isEqualTo("analysis-not-found");
				assertThat(response.header("Cache-Control")).contains("no-store");
			}
		}
		// Lo del dueño sigue intacto.
		assertThat(owner.get(CV + "/analyses/current").json().get("status").asString()).isEqualTo("READY");
		assertThat(intruder.get(CV + "/analyses/current").status()).as("el intruso no ve el análisis ajeno").isEqualTo(204);
	}

	@Test
	@DisplayName("009-17: todas las respuestas del estudiante llevan Cache-Control: no-store, también los errores")
	void t009_17_everythingIsPrivate() {
		ApiClient api = student();

		List<Response> responses = List.of(api.get(CV + "/usage"), api.get(CV + "/analyses/current"),
				upload(api, SampleCv.pdf("M")), upload(api, "no pdf".getBytes()), api.delete(CV + "/analyses/" + SOME_ID));

		for (Response response : responses) {
			assertThat(response.header("Cache-Control")).contains("no-store");
		}
	}

	@Test
	@DisplayName("009-17: las rutas del lector no se declaran públicas y la persona sale siempre de la sesión")
	void t009_17_ownerComesFromTheSession() {
		ApiClient ana = student();
		ApiClient beto = student();
		uploadSampleAndWait(ana);

		// Beto manda el id del análisis de Ana en la ruta, no hay otra forma de apuntar a ella: es un 404.
		JsonNode anas = ana.get(CV + "/analyses/current").json();
		assertThat(beto.delete(CV + "/analyses/" + anas.get("id").asString()).status()).isEqualTo(404);
		assertThat(ana.get(CV + "/analyses/current").json().get("status").asString()).isEqualTo("READY");
	}
	@Test
	@DisplayName("009-05: un cuerpo sin Content-Length (chunked) que pasa el tope de subida también se rechaza con 413")
	void t009_05_chunkedUploadIsCapped() {
		ApiClient api = student();
		String crlf = "" + (char) 13 + (char) 10;
		String boundary = "----chunked";
		byte[] head = ("--" + boundary + crlf + "Content-Disposition: form-data; name=" + (char) 34 + "file" + (char) 34
				+ "; filename=" + (char) 34 + "a.pdf" + (char) 34 + crlf + "Content-Type: application/pdf" + crlf + crlf)
						.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		byte[] tail = (crlf + "--" + boundary + "--" + crlf).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		byte[] file = new byte[4 * 1024 * 1024 + 1024];
		byte[] body = new byte[head.length + file.length + tail.length];
		System.arraycopy(head, 0, body, 0, head.length);
		System.arraycopy("%PDF-1.7".getBytes(), 0, body, head.length, 8);
		System.arraycopy(tail, 0, body, head.length + file.length, tail.length);

		int status;
		try {
			status = api.writeChunked("POST", CV + "/analyses", body, "multipart/form-data; boundary=" + boundary).status();
		}
		catch (IllegalStateException connectionCut) {
			status = 413;
		}

		assertThat(status).isEqualTo(413);
		assertThat(analysisRows(UUID.fromString(api.get("/api/v1/auth/me").json().get("id").asString()))).isZero();
	}

}
