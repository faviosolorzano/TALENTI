package com.talenti.identity.web;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.talenti.identity.service.LoginRateLimiter;
import com.talenti.shared.security.PublicRoute;
import com.talenti.shared.security.PublicRoutes;

/** Rutas públicas de identity y límite de solicitudes del ingreso. */
@Configuration(proxyBeanMethods = false)
class IdentityWebConfig implements WebMvcConfigurer {

	static final String API = "/api/v1";

	private final LoginRateLimiter loginRateLimiter;

	IdentityWebConfig(LoginRateLimiter loginRateLimiter) {
		this.loginRateLimiter = loginRateLimiter;
	}

	/** Todo lo demás de la API exige sesión (001-13). */
	@Bean
	PublicRoutes identityPublicRoutes() {
		return () -> List.of(
				PublicRoute.get(API + "/auth/csrf"),
				PublicRoute.get(API + "/legal/current"),
				PublicRoute.post(API + "/auth/register"),
				PublicRoute.post(API + "/auth/login"));
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new LoginRateLimitInterceptor(loginRateLimiter)).addPathPatterns(API + "/auth/login");
	}

}
