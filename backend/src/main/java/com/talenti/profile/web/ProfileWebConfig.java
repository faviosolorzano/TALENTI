package com.talenti.profile.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Todo {@code /profile} exige sesión (lo hace la cadena de seguridad, 001-13) y, además, rol de estudiante y
 * respuestas privadas: lo aplica un interceptor a TODA la ruta, así una ruta nueva no puede olvidarse de ello.
 */
@Configuration(proxyBeanMethods = false)
class ProfileWebConfig implements WebMvcConfigurer {

	static final String API = "/api/v1";

	static final String PROFILE = API + "/profile";

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new StudentOnlyInterceptor()).addPathPatterns(PROFILE, PROFILE + "/**");
	}

}
