package com.talenti;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pruebas con el perfil "prod" y un servidor HTTP real (el que usa el contenedor): el perfil arranca,
 * un error 500 no revela detalles internos y el 401 anónimo no entrega cookies.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("prod")
@Import({ TestcontainersConfiguration.class, ProdProfileTests.BoomConfiguration.class })
class ProdProfileTests {

	private static final String SECRET_DETAIL = "detalle-interno-que-no-debe-salir";

	@LocalServerPort
	private int port;

	@Autowired
	private Environment environment;

	private final HttpClient client = HttpClient.newHttpClient();

	@Test
	@DisplayName("T01-13: el perfil prod arranca con su configuración (apagado ordenado y registro en WARN)")
	void T01_13_prodProfileStarts() {
		assertThat(environment.getActiveProfiles()).containsExactly("prod");
		assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
		assertThat(environment.getProperty("logging.level.root")).isEqualTo("WARN");
	}

	@Test
	@DisplayName("T01-14: un error 500 no devuelve traza, mensaje ni nombre de excepción")
	void T01_14_serverErrorDoesNotLeakDetails() throws Exception {
		HttpResponse<String> response = get("/test-only/boom");

		assertThat(response.statusCode()).isEqualTo(500);
		assertThat(response.body())
				.doesNotContain(SECRET_DETAIL)
				.doesNotContain("IllegalStateException")
				.doesNotContain("trace")
				.doesNotContain("\"message\"")
				.doesNotContain("com.talenti");
	}

	@Test
	@DisplayName("T01-15: el 401 anónimo con servidor real no trae Set-Cookie (no se crea sesión)")
	void T01_15_anonymousUnauthorizedHasNoCookie() throws Exception {
		HttpResponse<String> response = get("/api/v1/cualquier-cosa");

		assertThat(response.statusCode()).isEqualTo(401);
		assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
	}

	private HttpResponse<String> get(String path) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
		return client.send(request, HttpResponse.BodyHandlers.ofString());
	}

	/** Ruta solo de prueba que lanza una excepción con un detalle que no debe filtrarse. */
	@TestConfiguration(proxyBeanMethods = false)
	static class BoomConfiguration {

		@Bean
		BoomController boomController() {
			return new BoomController();
		}

		/** Deja pública solo la ruta de prueba, para que el error 500 llegue al cliente. */
		@Bean
		@Order(0)
		SecurityFilterChain testOnlyChain(HttpSecurity http) throws Exception {
			http.securityMatcher("/test-only/**")
					.authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
			return http.build();
		}

	}

	@RestController
	static class BoomController {

		@GetMapping("/test-only/boom")
		String boom() {
			throw new IllegalStateException(SECRET_DETAIL);
		}

	}

}
