package com.talenti.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

/** Ingreso, bloqueo y límite de solicitudes: criterios 001-08 a 001-11 y 001-21. */
class LoginIntegrationTests extends IdentityIntegrationTest {

	private static final String LOGIN = "/api/v1/auth/login";

	private static final String ME = "/api/v1/auth/me";

	@Test
	@DisplayName("001-08: credenciales correctas devuelven 200 con el usuario y una cookie de sesión HttpOnly con SameSite=Lax")
	void t001_08_correctLoginReturnsUserAndSessionCookie() {
		RegisteredStudent student = registerStudent();
		ApiClient api = client();

		Response response = api.post(LOGIN, loginBody("  " + student.email().toUpperCase() + " ", student.password()));

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.json().get("id").asString()).isEqualTo(student.id().toString());
		assertThat(response.json().get("email").asString()).isEqualTo(student.email());
		assertThat(response.json().get("role").asString()).isEqualTo("STUDENT");
		String cookie = response.setCookie("JSESSIONID").orElseThrow();
		assertThat(cookie).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/");
		assertThat(api.get(ME).status()).isEqualTo(200);
	}

	@Test
	@DisplayName("001-08: el identificador de la sesión cambia al ingresar y el anterior deja de servir")
	void t001_08_sessionIdChangesOnLogin() {
		RegisteredStudent student = registerStudent();
		ApiClient api = client();
		api.post(LOGIN, loginBody(student.email(), student.password()));
		String firstSession = api.cookie("JSESSIONID");
		assertThat(firstSession).isNotBlank();

		// Se vuelve a ingresar presentando la sesión anterior (lo que haría un atacante con una sesión fijada).
		Response second = api.post(LOGIN, loginBody(student.email(), student.password()));

		assertThat(second.status()).isEqualTo(200);
		String secondSession = api.cookie("JSESSIONID");
		assertThat(secondSession).isNotBlank().isNotEqualTo(firstSession);
		ApiClient attacker = client();
		attacker.setCookie("JSESSIONID", firstSession);
		assertThat(attacker.get(ME).status()).isEqualTo(401);
	}

	@Test
	@DisplayName("001-09: correo inexistente y contraseña errónea responden 401 con exactamente el mismo mensaje")
	void t001_09_wrongCredentialsGiveTheSameGenericAnswer() {
		RegisteredStudent student = registerStudent();

		Response wrongPassword = client().post(LOGIN, loginBody(student.email(), validPassword()));
		Response unknownEmail = client().post(LOGIN, loginBody(uniqueEmail(), validPassword()));

		assertThat(wrongPassword.status()).isEqualTo(401);
		assertThat(unknownEmail.status()).isEqualTo(401);
		assertThat(wrongPassword.json().get("code").asString()).isEqualTo("invalid-credentials");
		assertThat(unknownEmail.body()).isEqualTo(wrongPassword.body());
		assertThat(wrongPassword.setCookies()).noneMatch(cookie -> cookie.startsWith("JSESSIONID"));
	}

	@Test
	@DisplayName("001-09: un ingreso sin correo o sin contraseña responde 422 y una contraseña absurdamente larga, 401")
	void t001_09_malformedLoginBodies() {
		ApiClient api = client();

		Response missing = api.post(LOGIN, java.util.Map.of("email", "a@b.test"));
		assertThat(missing.status()).isEqualTo(422);
		assertThat(missing.json().get("errors").get(0).get("rule").asString()).isEqualTo("required");

		Response huge = api.post(LOGIN, loginBody(uniqueEmail(), "A1" + "x".repeat(100)));
		assertThat(huge.status()).isEqualTo(401);
	}

	@Test
	@DisplayName("001-10: 5 intentos fallidos bloquean la cuenta 15 minutos, aun con la contraseña correcta y con el mismo mensaje")
	void t001_10_fiveFailuresLockTheAccountForFifteenMinutes() {
		RegisteredStudent student = registerStudent();
		ApiClient api = client();
		Response expected = api.post(LOGIN, loginBody(student.email(), validPassword()));
		for (int i = 1; i < 5; i++) {
			assertThat(api.post(LOGIN, loginBody(student.email(), validPassword())).status()).isEqualTo(401);
		}
		assertThat(jdbc.queryForObject("SELECT locked_until FROM app_user WHERE id = ?", Object.class, student.id()))
				.isNotNull();

		Response correctButLocked = api.post(LOGIN, loginBody(student.email(), student.password()));

		assertThat(correctButLocked.status()).isEqualTo(401);
		assertThat(correctButLocked.body()).isEqualTo(expected.body());

		clock.advance(Duration.ofMinutes(14));
		assertThat(api.post(LOGIN, loginBody(student.email(), student.password())).status()).isEqualTo(401);

		clock.advance(Duration.ofMinutes(1).plusSeconds(1));
		Response afterLock = api.post(LOGIN, loginBody(student.email(), student.password()));
		assertThat(afterLock.status()).isEqualTo(200);
		assertThat(count("SELECT failed_login_count FROM app_user WHERE id = ?", student.id())).isZero();
	}

	@Test
	@DisplayName("001-10: un ingreso correcto reinicia el contador, y tras el bloqueo un solo fallo no vuelve a bloquear")
	void t001_10_successResetsTheCounterAndExpiredLockStartsOver() {
		RegisteredStudent student = registerStudent();
		ApiClient api = client();
		for (int i = 0; i < 4; i++) {
			api.post(LOGIN, loginBody(student.email(), validPassword()));
		}
		assertThat(count("SELECT failed_login_count FROM app_user WHERE id = ?", student.id())).isEqualTo(4);

		assertThat(api.post(LOGIN, loginBody(student.email(), student.password())).status()).isEqualTo(200);
		assertThat(count("SELECT failed_login_count FROM app_user WHERE id = ?", student.id())).isZero();
		// Con el contador en cero, otros 4 fallos no bloquean: el siguiente ingreso correcto funciona.
		for (int i = 0; i < 4; i++) {
			api.post(LOGIN, loginBody(student.email(), validPassword()));
		}
		clock.advance(Duration.ofMinutes(1));
		ApiClient other = client();
		assertThat(other.post(LOGIN, loginBody(student.email(), student.password())).status()).isEqualTo(200);

		// Bloqueo, espera, un fallo y un ingreso correcto: el fallo cuenta como el primero, no como el sexto.
		for (int i = 0; i < 5; i++) {
			other.post(LOGIN, loginBody(student.email(), validPassword()));
		}
		clock.advance(Duration.ofMinutes(16));
		assertThat(other.post(LOGIN, loginBody(student.email(), validPassword())).status()).isEqualTo(401);
		assertThat(count("SELECT failed_login_count FROM app_user WHERE id = ?", student.id())).isEqualTo(1);
		assertThat(other.post(LOGIN, loginBody(student.email(), student.password())).status()).isEqualTo(200);
	}

	@Test
	@DisplayName("001-11: más de 10 solicitudes de ingreso por minuto desde un mismo origen responden 429 con Retry-After")
	void t001_11_moreThanTenLoginRequestsPerMinuteAreLimited() {
		ApiClient api = client();
		String email = uniqueEmail();
		for (int i = 1; i <= 10; i++) {
			assertThat(api.post(LOGIN, loginBody(email, validPassword())).status()).as("solicitud %d", i).isEqualTo(401);
		}

		Response limited = api.post(LOGIN, loginBody(email, validPassword()));

		assertThat(limited.status()).isEqualTo(429);
		assertThat(limited.json().get("code").asString()).isEqualTo("too-many-requests");
		assertThat(Long.parseLong(limited.header("Retry-After"))).isBetween(1L, 61L);
		// Otro origen no se ve afectado.
		assertThat(client().post(LOGIN, loginBody(email, validPassword())).status()).isEqualTo(401);
		// Pasada la ventana, el origen vuelve a poder intentar.
		clock.advance(Duration.ofSeconds(61));
		assertThat(api.post(LOGIN, loginBody(email, validPassword())).status()).isEqualTo(401);
	}

	@Test
	@DisplayName("001-21: los usuarios COMPANY y ADMIN ingresan y la API los autentica con su rol")
	void t001_21_companyAndAdminAuthenticateWithTheirRole() {
		for (Role role : List.of(Role.COMPANY, Role.ADMIN)) {
			CreatedUser user = createUser(role);
			ApiClient api = client();

			Response login = api.post(LOGIN, loginBody(user.email(), user.password()));

			assertThat(login.status()).isEqualTo(200);
			assertThat(login.json().get("role").asString()).isEqualTo(role.name());
			Response me = api.get(ME);
			assertThat(me.status()).isEqualTo(200);
			assertThat(me.json().get("role").asString()).isEqualTo(role.name());
			assertThat(me.json().get("id").asString()).isEqualTo(user.id().toString());
		}
	}

}
