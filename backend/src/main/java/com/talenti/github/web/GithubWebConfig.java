package com.talenti.github.web;

import java.time.Clock;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.talenti.github.config.GithubProperties;
import com.talenti.shared.security.PublicRoute;
import com.talenti.shared.security.PublicRoutes;

/**
 * Todo {@code /github} exige sesión (lo hace la cadena de seguridad) y, además, rol de estudiante, respuestas privadas
 * y un límite general por persona: lo aplica un interceptor a TODA la ruta (013-23).
 *
 * <p>Única excepción: {@code GET /github/oauth/callback}. Es una navegación del navegador que llega desde GitHub, así
 * que se declara pública para que la cadena de seguridad no responda un 401 en JSON; el controlador comprueba la
 * sesión y siempre redirige ({@code session-expired} si no hay). No necesita CSRF (es un GET y lo protege el
 * {@code state} de un solo uso, 013-26). La cookie de sesión es {@code SameSite=Lax}: viaja en esa navegación GET de
 * primer nivel desde github.com (con {@code Strict} no viajaría).
 */
@Configuration(proxyBeanMethods = false)
class GithubWebConfig implements WebMvcConfigurer {

	static final String GITHUB = "/api/v1/github";

	static final String CALLBACK = GITHUB + "/oauth/callback";

	private final GithubProperties properties;

	private final Clock clock;

	GithubWebConfig(GithubProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
	}

	@Bean
	PublicRoutes githubPublicRoutes() {
		return () -> List.of(PublicRoute.get(CALLBACK));
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new StudentOnlyInterceptor(new PersonRateLimiter(properties.requestsPerMinute(), clock)))
				.addPathPatterns(GITHUB, GITHUB + "/**").excludePathPatterns(CALLBACK);
	}

}
