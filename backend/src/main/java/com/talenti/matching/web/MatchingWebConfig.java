package com.talenti.matching.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Todo {@code /compatibility} exige sesión (lo hace la cadena de seguridad: estas rutas NO se declaran en
 * {@code PublicRoutes}) y, además, rol de estudiante y respuestas privadas: lo aplica un interceptor a TODA la ruta,
 * así una ruta nueva no puede olvidarlo (007-14).
 */
@Configuration(proxyBeanMethods = false)
class MatchingWebConfig implements WebMvcConfigurer {

	static final String API = "/api/v1";

	static final String COMPATIBILITY = API + "/compatibility";

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new StudentOnlyInterceptor()).addPathPatterns(COMPATIBILITY, COMPATIBILITY + "/**");
	}

}
