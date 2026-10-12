package com.talenti.identity.web;

import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.talenti.identity.service.RateLimiter;
import com.talenti.shared.security.PublicRoute;
import com.talenti.shared.security.PublicRoutes;

/** Rutas públicas de identity y límite de solicitudes del ingreso y del registro. */
@Configuration(proxyBeanMethods = false)
class IdentityWebConfig implements WebMvcConfigurer {

	static final String API = "/api/v1";

	private final RateLimiter loginRateLimiter;

	private final RateLimiter registerRateLimiter;

	IdentityWebConfig(@Qualifier("loginRateLimiter") RateLimiter loginRateLimiter,
			@Qualifier("registerRateLimiter") RateLimiter registerRateLimiter) {
		this.loginRateLimiter = loginRateLimiter;
		this.registerRateLimiter = registerRateLimiter;
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
		// Después de la verificación CSRF y antes de leer el cuerpo; cada ruta cuenta en su propio cubo.
		registry.addInterceptor(new RateLimitInterceptor(loginRateLimiter)).addPathPatterns(API + "/auth/login");
		registry.addInterceptor(new RateLimitInterceptor(registerRateLimiter)).addPathPatterns(API + "/auth/register");
	}

}
