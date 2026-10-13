package com.talenti.profile.web;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
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

	/** Tope de 64 KB que cuenta los bytes realmente leídos, también con cuerpo chunked. */
	@Bean
	FilterRegistrationBean<BodyLimitFilter> profileBodyLimit(
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
		FilterRegistrationBean<BodyLimitFilter> registration = new FilterRegistrationBean<>(
				new BodyLimitFilter(StudentOnlyInterceptor.MAX_BODY_BYTES, exceptionResolver));
		registration.setName("profileBodyLimit");
		registration.addUrlPatterns(PROFILE, PROFILE + "/*");
		return registration;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new StudentOnlyInterceptor()).addPathPatterns(PROFILE, PROFILE + "/**");
	}

}
