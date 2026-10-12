package com.talenti.identity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.talenti.TestcontainersConfiguration;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;
import com.talenti.support.TestClockConfiguration;

/**
 * Identidad con el perfil de producción y un servidor real: atributos de las cookies (001-08) y expiración
 * real de la sesión por inactividad (001-14).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class,
		IdentityProdProfileTests.ShortSessionConfiguration.class })
class IdentityProdProfileTests extends IdentityIntegrationTest {

	@Autowired
	private Environment environment;

	@Test
	@DisplayName("001-08: en producción la cookie de sesión es HttpOnly, Secure y SameSite=Lax, y la XSRF-TOKEN es Secure, legible y SameSite=Lax")
	void t001_08_productionCookieAttributes() {
		RegisteredStudent student = registerStudent();
		ApiClient api = client();

		Response login = api.post("/api/v1/auth/login", loginBody(student.email(), student.password()));

		assertThat(login.status()).isEqualTo(200);
		assertThat(login.setCookie("JSESSIONID").orElseThrow())
				.contains("HttpOnly").contains("Secure").contains("SameSite=Lax").contains("Path=/");
		assertThat(login.setCookie("XSRF-TOKEN").orElseThrow())
				.contains("Secure").contains("SameSite=Lax").doesNotContain("HttpOnly");
		Response register = client().post("/api/v1/auth/register", registerBody(uniqueEmail(), validPassword()));
		assertThat(register.setCookie("JSESSIONID").orElseThrow()).contains("HttpOnly").contains("Secure");
	}

	@Test
	@DisplayName("001-14: con el servidor real, una sesión inactiva más allá de su límite responde 401 session-expired")
	void t001_14_realSessionExpiresAfterInactivity() throws Exception {
		assertThat(environment.getProperty("server.servlet.session.timeout")).isEqualTo("30m");
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		assertThat(api.get("/api/v1/auth/me").status()).isEqualTo(200);
		// Solo para la prueba: se acorta el límite de ESTA sesión a 1 segundo en vez de esperar 30 minutos.
		assertThat(api.get("/test-only/short-session").status()).isEqualTo(204);

		Thread.sleep(2_500);
		Response expired = api.get("/api/v1/auth/me");

		assertThat(expired.status()).isEqualTo(401);
		assertThat(expired.json().get("code").asString()).isEqualTo("session-expired");
		assertThat(expired.json().get("detail").asString()).contains("expiró");
	}

	@Test
	@DisplayName("001-13: en producción las rutas públicas y el 401 anónimo no crean sesión ni entregan JSESSIONID")
	void t001_13_anonymousRequestsDoNotCreateSessions() {
		ApiClient api = client();

		assertThat(api.get("/api/v1/legal/current").setCookies()).isEmpty();
		assertThat(api.get("/api/v1/auth/me").setCookies()).isEmpty();
		Response csrf = api.get("/api/v1/auth/csrf");
		assertThat(csrf.setCookies()).hasSize(1).allMatch(cookie -> cookie.startsWith("XSRF-TOKEN="));
		Response failedLogin = api.post("/api/v1/auth/login", loginBody(uniqueEmail(), validPassword()));
		assertThat(failedLogin.status()).isEqualTo(401);
		assertThat(failedLogin.setCookies()).noneMatch(cookie -> cookie.startsWith("JSESSIONID"));
	}

	/** Ruta solo de prueba que acorta el tiempo máximo de inactividad de la sesión en curso. */
	@TestConfiguration(proxyBeanMethods = false)
	static class ShortSessionConfiguration {

		@Bean
		ShortSessionController shortSessionController() {
			return new ShortSessionController();
		}

		@Bean
		@Order(0)
		SecurityFilterChain shortSessionChain(HttpSecurity http) throws Exception {
			http.securityMatcher("/test-only/**").authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
			return http.build();
		}

	}

	@RestController
	static class ShortSessionController {

		@GetMapping("/test-only/short-session")
		org.springframework.http.ResponseEntity<Void> shorten(HttpServletRequest request) {
			request.getSession(false).setMaxInactiveInterval(1);
			return org.springframework.http.ResponseEntity.noContent().build();
		}

	}

}
