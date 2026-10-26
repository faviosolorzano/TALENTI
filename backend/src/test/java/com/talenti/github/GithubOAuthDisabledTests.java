package com.talenti.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.talenti.github.config.GithubProperties;
import com.talenti.profile.ProfileIntegrationTest;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

/**
 * 013-24 con la configuración por defecto (la de cualquier instalación sin variables de GitHub): la conexión está
 * apagada y lo demás no cambia. Usa el mismo contexto que las pruebas del perfil. Ninguna dirección de GitHub de este
 * contexto sale de la máquina (sección 9).
 */
class GithubOAuthDisabledTests extends ProfileIntegrationTest {

	private static final String GITHUB = "/api/v1/github";

	@Autowired
	private GithubProperties properties;

	@Test
	@DisplayName("013-24: apagada por defecto, start y la vuelta responden 503 github-oauth-disabled")
	void t013_24_disabledByDefaultIs503() {
		ApiClient api = student();

		Response start = api.post(GITHUB + "/oauth/start", null);
		Response callback = api.get(GITHUB + "/oauth/callback?code=abc&state=def");

		assertThat(properties.oauth().enabled()).isFalse();
		for (Response response : new Response[] { start, callback }) {
			assertThat(response.status()).isEqualTo(503);
			assertThat(response.json().get("code").asString()).isEqualTo("github-oauth-disabled");
			assertThat(response.json().get("detail").asString()).isNotBlank();
		}
		assertThat(count("SELECT count(*) FROM github_oauth_state")).isZero();
	}

	@Test
	@DisplayName("013-24: apagada, lo demás no cambia: sin vínculo GET /github da 204 y repositorios 409 not-verified")
	void t013_24_restUnchanged() {
		ApiClient api = student();

		assertThat(api.get(GITHUB).status()).isEqualTo(204);
		Response repositories = api.get(GITHUB + "/repositories");
		assertThat(repositories.status()).isEqualTo(409);
		assertThat(repositories.json().get("code").asString()).isEqualTo("not-verified");
		assertThat(api.delete(GITHUB).status()).isEqualTo(204);
	}

	@Test
	@DisplayName("Sección 9: en las pruebas ninguna dirección de GitHub apunta a github.com ni a api.github.com")
	void noRealGithubInTests() {
		for (String url : new String[] { properties.baseUrl(), properties.oauth().baseUrl() }) {
			String host = URI.create(url).getHost();
			assertThat(host).isEqualTo("127.0.0.1");
			assertThat(host).doesNotContain("github.com");
		}
	}

}
