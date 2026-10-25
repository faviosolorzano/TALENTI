package com.talenti.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.talenti.profile.ProfileIntegrationTest;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;
import com.talenti.support.FakeGithub;
import com.talenti.support.NoRealGithub;

import tools.jackson.databind.JsonNode;

/**
 * Base de las pruebas de integración de GitHub (spec 013): aplicación completa con servidor HTTP real, PostgreSQL real
 * y un GitHub SIMULADO ({@link FakeGithub}: autorización, canje y API) en un puerto local. Ninguna prueba sale a
 * github.com. La conexión está encendida con un id y un secreto inventados. Con los datos de ejemplo cargados (para
 * probar la compatibilidad). Todos los datos son inventados.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "talenti.demo-data.enabled=true")
public abstract class GithubIntegrationTest extends ProfileIntegrationTest {

	protected static final String GITHUB = "/api/v1/github";

	protected static final String CLIENT_ID = "cliente-de-prueba";

	/** Secreto inventado con una marca única: no debe aparecer en registros ni respuestas (013-17). */
	protected static final String CLIENT_SECRET = "secretoMarcaQz81xv";

	protected static final String RETURN_URL = "http://localhost:3000/perfil";

	protected static final String REDIRECT_URI = "http://localhost:3000/api/v1/github/oauth/callback";

	protected static final FakeGithub fake = FakeGithub.shared();

	static {
		// Si algo intentara salir a github.com, se anota y no llega (sección 9).
		NoRealGithub.install();
	}

	@AfterAll
	static void nothingWentToRealGithub() {
		assertThat(NoRealGithub.attempts()).as("intentos de llamar a GitHub real").isEmpty();
	}

	@DynamicPropertySource
	static void githubProperties(DynamicPropertyRegistry registry) {
		registry.add("talenti.github.base-url", fake::baseUrl);
		registry.add("talenti.github.oauth.base-url", fake::baseUrl);
		registry.add("talenti.github.oauth.enabled", () -> "true");
		registry.add("talenti.github.oauth.client-id", () -> CLIENT_ID);
		registry.add("talenti.github.oauth.client-secret", () -> CLIENT_SECRET);
		registry.add("talenti.github.oauth.redirect-uri", () -> REDIRECT_URI);
		registry.add("talenti.github.oauth.return-url", () -> RETURN_URL);
	}

	@BeforeEach
	void resetFakeGithub() {
		fake.reset();
		fake.useClock(clock);
	}

	/** Un estudiante con GitHub vinculado y su cuenta simulada. */
	protected record Linked(RegisteredStudent student, ApiClient api, FakeGithub.Account account) {
	}

	/** {@code POST /github/oauth/start}: devuelve la dirección de autorización. */
	protected static String authorizeUrl(ApiClient api) {
		Response response = api.post(GITHUB + "/oauth/start", null);
		assertThat(response.status()).as("start: %s", response.body()).isEqualTo(200);
		return response.json().get("authorizeUrl").asString();
	}

	protected static Map<String, String> query(String url) {
		Map<String, String> result = new LinkedHashMap<>();
		String query = URI.create(url).getRawQuery();
		if (query == null) {
			return result;
		}
		for (String pair : query.split("&")) {
			int eq = pair.indexOf('=');
			result.put(URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8),
					eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return result;
	}

	protected static String start(ApiClient api) {
		return query(authorizeUrl(api)).get("state");
	}

	/** La vuelta desde GitHub tal como la haría el navegador (GET, sin cabecera CSRF). */
	protected static Response callback(ApiClient api, String code, String state) {
		StringBuilder path = new StringBuilder(GITHUB + "/oauth/callback?");
		if (code != null) {
			path.append("code=").append(code).append('&');
		}
		if (state != null) {
			path.append("state=").append(java.net.URLEncoder.encode(state, StandardCharsets.UTF_8));
		}
		return api.get(path.toString());
	}

	/** El {@code code} de error de la redirección, o {@code linked}. */
	protected static String outcome(Response response) {
		assertThat(response.status()).as("la vuelta siempre redirige: %s", response.body()).isEqualTo(302);
		String location = response.header("Location");
		assertThat(location).startsWith(RETURN_URL + "?github=");
		Map<String, String> query = query(location);
		return "linked".equals(query.get("github")) ? "linked" : query.get("code");
	}

	/** Registra un estudiante y le vincula una cuenta simulada nueva (sin repositorios salvo que se agreguen antes). */
	protected Linked linkNew(FakeGithub.Account account) {
		RegisteredStudent student = registerStudent();
		String state = start(student.api());
		assertThat(outcome(callback(student.api(), fake.authorize(account), state))).isEqualTo("linked");
		return new Linked(student, student.api(), account);
	}

	protected Linked linkNew(String loginPrefix) {
		return linkNew(fake.account(loginPrefix));
	}

	protected static JsonNode repositories(ApiClient api) {
		return ok(api.get(GITHUB + "/repositories"));
	}

	/** El id (de Talenti) del repositorio con ese nombre. */
	protected static String repoId(JsonNode list, String name) {
		for (JsonNode item : list.get("items")) {
			if (item.get("name").asString().equals(name)) {
				return item.get("id").asString();
			}
		}
		throw new AssertionError("No está el repositorio " + name);
	}

	protected static Response select(ApiClient api, List<String> ids) {
		return api.put(GITHUB + "/repositories/selection", body("repoIds", ids));
	}

	protected static Map<String, Object> decision(String slug, String decision, Object level) {
		return body("slug", slug, "decision", decision, "level", level);
	}

	@SafeVarargs
	protected static Response decide(ApiClient api, Map<String, Object>... items) {
		return api.post(GITHUB + "/suggestions/decisions", body("items", List.of(items)));
	}

	/** Cuántas filas de TODA la base contienen el texto (se busca en el texto de cada fila de cada tabla). */
	protected int rowsContaining(String text) {
		List<String> tables = jdbc.queryForList(
				"SELECT tablename FROM pg_tables WHERE schemaname = 'public'", String.class);
		int total = 0;
		for (String table : tables) {
			total += count("SELECT count(*) FROM \"" + table + "\" t WHERE t::text LIKE ?", "%" + text + "%");
		}
		return total;
	}

}
