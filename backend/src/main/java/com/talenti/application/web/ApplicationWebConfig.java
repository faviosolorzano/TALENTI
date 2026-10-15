package com.talenti.application.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Todo {@code /applications} exige sesión (lo hace la cadena de seguridad: estas rutas NO se declaran en
 * {@code PublicRoutes}) y, además, rol de estudiante y respuestas privadas: lo aplica un interceptor a TODA la ruta,
 * así una ruta nueva no puede olvidarlo (008-10).
 */
@Configuration(proxyBeanMethods = false)
class ApplicationWebConfig implements WebMvcConfigurer {

	static final String APPLICATIONS = "/api/v1/applications";

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new StudentOnlyInterceptor()).addPathPatterns(APPLICATIONS, APPLICATIONS + "/**");
	}

}
