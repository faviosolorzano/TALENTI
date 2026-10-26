package com.talenti.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.github.config.GithubProperties;
import com.talenti.support.NoRealGithub;

import tools.jackson.databind.json.JsonMapper;

/**
 * Sección 9 de la spec 013: la guardia de las pruebas funciona. Un cliente configurado con las direcciones reales de
 * GitHub (las de producción) no llega a salir: la guardia lo anota y lo manda a un puerto local cerrado.
 */
class NoRealGithubGuardTests {

	@Test
	@DisplayName("Sección 9: si algo intenta llamar a api.github.com en una prueba, la guardia lo detiene y lo anota")
	void guardStopsRealGithub() {
		NoRealGithub.install();
		GithubProperties production = new GithubProperties("https://api.github.com", "2022-11-28", "",
				new GithubProperties.OAuth(false, "", "", "https://github.com", "http://localhost/cb", "http://localhost",
						Duration.ofMinutes(10)),
				false, 1, 6, 10, Duration.ofHours(24), Duration.ofMinutes(10), 5, Duration.ofSeconds(1),
				Duration.ofSeconds(1), 30, ZoneId.of("America/Lima"), "classpath:github/technology-map.properties");
		GithubClient client = new GithubClient(production, new RateBudget(5, Clock.systemUTC()),
				JsonMapper.builder().build(), Clock.systemUTC());
		try {
			assertThatThrownBy(() -> client.userById(1)).isInstanceOf(GithubUnavailableException.class);
			assertThat(NoRealGithub.attempts()).containsExactly("api.github.com");
		}
		finally {
			NoRealGithub.forget();
		}
	}

}
