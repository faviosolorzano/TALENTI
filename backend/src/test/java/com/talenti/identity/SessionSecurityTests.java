package com.talenti.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

/** Sesión, rutas privadas, CSRF y acceso por objeto: criterios 001-12 a 001-16. */
class SessionSecurityTests extends IdentityIntegrationTest {

	private static final String LOGIN = "/api/v1/auth/login";

	private static final String LOGOUT = "/api/v1/auth/logout";

	private static final String ME = "/api/v1/auth/me";

	private static final String REGISTER = "/api/v1/auth/register";

	@Autowired
	private Environment environment;

	@Test
	@DisplayName("001-12: cerrar sesión invalida la sesión en el servidor, borra la cookie y la cookie anterior da 401")
	void t001_12_logoutInvalidatesTheSession() {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		String oldSession = api.cookie("JSESSIONID");
		assertThat(api.get(ME).status()).isEqualTo(200);

		Response logout = api.post(LOGOUT, null);

		assertThat(logout.status()).isEqualTo(204);
		String deleted = logout.setCookie("JSESSIONID").orElseThrow();
		assertThat(deleted).contains("Max-Age=0").contains("HttpOnly");
		assertThat(api.cookie("JSESSIONID")).isNull();
		// Quien conserve la cookie anterior (por ejemplo, copiada antes) ya no entra.
		ApiClient reuse = client();
		reuse.setCookie("JSESSIONID", oldSession);
		Response afterLogout = reuse.get(ME);
		assertThat(afterLogout.status()).isEqualTo(401);
		assertThat(afterLogout.setCookies()).noneMatch(cookie -> cookie.startsWith("JSESSIONID=") && !cookie.contains("Max-Age=0"));
		// Y se puede volver a ingresar con la misma interfaz: el cierre dejó un token CSRF nuevo.
		assertThat(api.post(LOGIN, loginBody(student.email(), student.password())).status()).isEqualTo(200);
	}

	@Test
	@DisplayName("001-12: cerrar sesión sin sesión responde 401")
	void t001_12_logoutWithoutSessionIs401() {
		Response response = client().post(LOGOUT, null);

		assertThat(response.status()).isEqualTo(401);
		assertThat(response.setCookies()).noneMatch(cookie -> cookie.startsWith("JSESSIONID"));
	}

	@Test
	@DisplayName("001-13: un visitante sin sesión recibe 401 en cualquier ruta privada, sin cookie de sesión")
	void t001_13_anonymousVisitorGets401OnPrivateRoutes() {
		ApiClient api = client();

		for (String path : new String[] { ME, "/api/v1/cualquier-cosa", "/api/v1/profile", "/actuator/env" }) {
			Response response = api.get(path);
			assertThat(response.status()).as(path).isEqualTo(401);
			assertThat(response.json().get("code").asString()).as(path).isEqualTo("unauthenticated");
			assertThat(response.setCookies()).as(path).noneMatch(cookie -> cookie.startsWith("JSESSIONID"));
		}
		Response accept = api.post("/api/v1/legal/accept", Map.of("legalAccepted", true, "legalVersion", "v1"));
		assertThat(accept.status()).isEqualTo(401);
		assertThat(api.get("/api/v1/legal/current").status()).isEqualTo(200);
	}

	@Test
	@DisplayName("001-14: una cookie de sesión que ya no es válida da 401 con el código session-expired")
	void t001_14_staleSessionCookieReportsSessionExpired() {
		ApiClient api = client();
		api.setCookie("JSESSIONID", "A".repeat(32));

		Response stale = api.get(ME);
		Response withoutCookie = client().get(ME);

		assertThat(stale.status()).isEqualTo(401);
		assertThat(stale.json().get("code").asString()).isEqualTo("session-expired");
		assertThat(withoutCookie.json().get("code").asString()).isEqualTo("unauthenticated");
		// Las rutas públicas siguen funcionando aunque la cookie esté vencida.
		assertThat(api.get("/api/v1/legal/current").status()).isEqualTo(200);
	}

	@Test
	@DisplayName("001-14: la sesión expira tras 30 minutos de inactividad (valor configurado)")
	void t001_14_sessionTimeoutIsThirtyMinutes() {
		assertThat(environment.getProperty("server.servlet.session.timeout", Duration.class))
				.isEqualTo(Duration.ofMinutes(30));
	}

	@Test
	@DisplayName("001-15: un POST sin token CSRF válido (registro, ingreso o cierre de sesión) responde 403")
	void t001_15_postWithoutValidCsrfTokenIs403() {
		RegisteredStudent student = registerStudent();
		ApiClient anonymous = client();
		ApiClient loggedIn = student.api();

		assertThat(anonymous.postWithoutCsrf(REGISTER, registerBody(uniqueEmail(), validPassword())).status())
				.isEqualTo(403);
		Response login = anonymous.postWithoutCsrf(LOGIN, loginBody(student.email(), student.password()));
		assertThat(login.status()).isEqualTo(403);
		assertThat(login.json().get("code").asString()).isEqualTo("csrf-invalid");
		assertThat(loggedIn.postWithoutCsrf(LOGOUT, null).status()).isEqualTo(403);
		assertThat(loggedIn.postWithCsrf(LOGOUT, null, "token-falso").status()).isEqualTo(403);
		// Nada de lo anterior cerró la sesión ni creó cuentas.
		assertThat(loggedIn.get(ME).status()).isEqualTo(200);
	}

	@Test
	@DisplayName("001-15: flujo de una aplicación de una sola página: la cookie XSRF-TOKEN se lee y vuelve en X-XSRF-TOKEN")
	void t001_15_singlePageAppCsrfFlowWorksEndToEnd() {
		RegisteredStudent student = registerStudent();
		ApiClient spa = client();

		// 1. El frontend pide el token: queda la cookie XSRF-TOKEN, legible por JavaScript (no HttpOnly).
		Response csrf = spa.get("/api/v1/auth/csrf");
		assertThat(csrf.status()).isEqualTo(204);
		String cookie = csrf.setCookie("XSRF-TOKEN").orElseThrow();
		assertThat(cookie).doesNotContain("HttpOnly").contains("SameSite=Lax").contains("Path=/");
		assertThat(csrf.setCookies()).noneMatch(c -> c.startsWith("JSESSIONID"));
		String token = spa.cookie("XSRF-TOKEN");
		assertThat(token).isNotBlank();

		// 2. Sin la cabecera, 403; con un valor que no es el de la cookie, 403; con el valor de la cookie, pasa.
		assertThat(spa.postWithoutCsrf(LOGIN, loginBody(student.email(), student.password())).status()).isEqualTo(403);
		assertThat(spa.postWithCsrf(LOGIN, loginBody(student.email(), student.password()), token + "x").status())
				.isEqualTo(403);
		Response login = spa.postWithCsrf(LOGIN, loginBody(student.email(), student.password()), token);
		assertThat(login.status()).isEqualTo(200);

		// 3. Al ingresar el token se renueva: el frontend vuelve a leer la cookie y la nueva funciona.
		String renewed = spa.cookie("XSRF-TOKEN");
		assertThat(renewed).isNotBlank().isNotEqualTo(token);
		assertThat(login.setCookie("XSRF-TOKEN")).isPresent();
		Response logout = spa.postWithCsrf(LOGOUT, null, renewed);
		assertThat(logout.status()).isEqualTo(204);

		// 4. Tras cerrar sesión también queda un token nuevo en la cookie, listo para el siguiente formulario.
		String afterLogout = spa.cookie("XSRF-TOKEN");
		assertThat(afterLogout).isNotBlank().isNotEqualTo(renewed);
		assertThat(spa.postWithCsrf(LOGIN, loginBody(student.email(), student.password()), afterLogout).status())
				.isEqualTo(200);
	}

	@Test
	@DisplayName("001-16: cada estudiante con sesión ve solo sus propios datos en \"mi usuario\"")
	void t001_16_eachStudentSeesOnlyTheirOwnData() {
		RegisteredStudent ana = registerStudent();
		RegisteredStudent luis = registerStudent();

		Response anaMe = ana.api().get(ME);
		Response luisMe = luis.api().get(ME);

		assertThat(anaMe.json().get("id").asString()).isEqualTo(ana.id().toString());
		assertThat(anaMe.json().get("email").asString()).isEqualTo(ana.email());
		assertThat(luisMe.json().get("id").asString()).isEqualTo(luis.id().toString());
		assertThat(anaMe.body()).doesNotContain(luis.email()).doesNotContain(luis.id().toString());
		// Ninguna ruta recibe el id de otra persona: aunque se envíe, la API responde con los datos de la sesión.
		Response withForeignId = ana.api().get(ME + "?id=" + luis.id() + "&userId=" + luis.id());
		assertThat(withForeignId.json().get("id").asString()).isEqualTo(ana.id().toString());
		assertThat(ana.api().get(ME + "/" + luis.id()).status()).isEqualTo(404);
	}

}
