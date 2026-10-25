package com.talenti.github.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import com.talenti.github.client.GithubClient;
import com.talenti.github.client.RateBudget;
import com.talenti.github.domain.TechnologyMap;

import tools.jackson.databind.json.JsonMapper;

/**
 * Piezas del módulo github (spec 013). Con {@code talenti.github.oauth.enabled=true} y sin {@code GITHUB_CLIENT_ID},
 * {@code GITHUB_CLIENT_SECRET} o {@code redirect-uri}, el arranque falla nombrando lo que falta, sin mostrar valores
 * (013-24).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GithubProperties.class)
public class GithubConfig {

	@Bean
	RateBudget githubRateBudget(GithubProperties properties, Clock clock) {
		return GithubClient.budget(properties, clock);
	}

	@Bean
	GithubClient githubClient(GithubProperties properties, RateBudget githubRateBudget, JsonMapper json, Clock clock) {
		checkOAuth(properties.oauth());
		return new GithubClient(properties, githubRateBudget, json, clock);
	}

	@Bean
	TechnologyMap githubTechnologyMap(GithubProperties properties, ResourceLoader resources) throws IOException {
		try (InputStream input = resources.getResource(properties.technologyMap()).getInputStream()) {
			return TechnologyMap.parse(List.of(new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")));
		}
	}

	/** 013-24: encendido, cada variable obligatoria debe tener valor. El mensaje nombra la variable, nunca su valor. */
	public static void checkOAuth(GithubProperties.OAuth oauth) {
		if (!oauth.enabled()) {
			return;
		}
		List<String> missing = new ArrayList<>();
		if (oauth.clientId().isEmpty()) {
			missing.add("GITHUB_CLIENT_ID");
		}
		if (oauth.clientSecret().isEmpty()) {
			missing.add("GITHUB_CLIENT_SECRET");
		}
		if (oauth.redirectUri().isEmpty()) {
			missing.add("TALENTI_GITHUB_OAUTH_REDIRECT_URI");
		}
		if (!missing.isEmpty()) {
			throw new IllegalStateException("La conexión con GitHub está encendida (TALENTI_GITHUB_OAUTH_ENABLED=true) "
					+ "pero falta " + String.join(", ", missing) + ". Ponla en tu .env o apaga la conexión con "
					+ "TALENTI_GITHUB_OAUTH_ENABLED=false.");
		}
	}

}
