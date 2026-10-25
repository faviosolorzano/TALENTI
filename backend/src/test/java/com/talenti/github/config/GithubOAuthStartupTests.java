package com.talenti.github.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import tools.jackson.databind.json.JsonMapper;

/**
 * 013-24: encender la conexión con GitHub sin {@code GITHUB_CLIENT_ID}, {@code GITHUB_CLIENT_SECRET} o
 * {@code redirect-uri} hace fallar el arranque con un mensaje que nombra lo que falta, sin mostrar valores. Se prueba
 * con la configuración real del módulo en un contexto mínimo.
 */
class GithubOAuthStartupTests {

	private static final String SECRET_VALUE = "valorSecretoNoSeMuestra93";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(GithubConfig.class)
			.withBean(Clock.class, Clock::systemUTC)
			.withBean(JsonMapper.class, () -> JsonMapper.builder().build());

	@Test
	@DisplayName("013-24: encendida sin GITHUB_CLIENT_ID ni GITHUB_CLIENT_SECRET, el arranque falla y nombra las dos variables")
	void t013_24_missingBothFails() {
		runner.withPropertyValues("talenti.github.oauth.enabled=true").run(context -> {
			assertThat(context).hasFailed();
			assertThat(rootMessage(context.getStartupFailure())).contains("GITHUB_CLIENT_ID").contains("GITHUB_CLIENT_SECRET");
		});
	}

	@Test
	@DisplayName("013-24: el mensaje nombra solo la que falta y nunca muestra el valor de la otra")
	void t013_24_messageNeverShowsValues() {
		runner.withPropertyValues("talenti.github.oauth.enabled=true", "talenti.github.oauth.client-secret=" + SECRET_VALUE)
				.run(context -> {
					assertThat(context).hasFailed();
					String message = rootMessage(context.getStartupFailure());
					assertThat(message).contains("GITHUB_CLIENT_ID").doesNotContain("GITHUB_CLIENT_SECRET")
							.doesNotContain(SECRET_VALUE);
				});
		runner.withPropertyValues("talenti.github.oauth.enabled=true", "talenti.github.oauth.client-id=id",
				"talenti.github.oauth.client-secret=" + SECRET_VALUE, "talenti.github.oauth.redirect-uri= ")
				.run(context -> assertThat(rootMessage(context.getStartupFailure()))
						.contains("TALENTI_GITHUB_OAUTH_REDIRECT_URI"));
	}

	@Test
	@DisplayName("013-24: apagada (por defecto) arranca sin variables; encendida con todo, también")
	void t013_24_otherCombinationsStart() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(GithubProperties.class).oauth().enabled()).isFalse();
			assertThat(context.getBean(GithubProperties.class).toString()).doesNotContain(SECRET_VALUE);
		});
		runner.withPropertyValues("talenti.github.oauth.enabled=true", "talenti.github.oauth.client-id=id",
				"talenti.github.oauth.client-secret=" + SECRET_VALUE).run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context.getBean(GithubProperties.class).toString()).doesNotContain(SECRET_VALUE);
				});
	}

	private static String rootMessage(Throwable failure) {
		Throwable current = failure;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		return current.getMessage();
	}

}
