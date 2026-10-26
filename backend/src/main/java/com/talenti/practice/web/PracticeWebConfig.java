package com.talenti.practice.web;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.talenti.identity.Role;
import com.talenti.practice.config.DoraProperties;

/**
 * Todas las rutas de DORA exigen sesión (no se declaran en {@code PublicRoutes}) y, además, rol y respuestas privadas:
 * lo aplica un interceptor a TODA la ruta, así una ruta nueva no puede olvidarlo (012-32). La latencia es solo para
 * administración (012-15).
 */
@Configuration(proxyBeanMethods = false)
class PracticeWebConfig implements WebMvcConfigurer {

	static final String API = "/api/v1";

	static final String PRACTICE = API + "/practice";

	static final String DORA = API + "/dora";

	static final String ADMIN = API + "/admin/dora";

	static final String INTERVIEW = API + "/applications/{applicationId}/interview";

	/** Margen del cuerpo multipart sobre el tamaño del audio (cabeceras y campos de cada parte). */
	private static final long MULTIPART_OVERHEAD = 16 * 1024;

	@Bean
	FilterRegistrationBean<AudioSizeFilter> doraAudioSizeFilter(DoraProperties properties,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
		FilterRegistrationBean<AudioSizeFilter> registration = new FilterRegistrationBean<>(
				new AudioSizeFilter(properties.maxAudioBytes().toBytes() + MULTIPART_OVERHEAD, exceptionResolver));
		registration.setName("doraAudioSizeFilter");
		registration.addUrlPatterns(DORA + "/sessions/*");
		return registration;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new RoleInterceptor(Role.STUDENT)).addPathPatterns(PRACTICE, PRACTICE + "/**", DORA,
				DORA + "/**", API + "/applications/*/interview");
		registry.addInterceptor(new RoleInterceptor(Role.ADMIN)).addPathPatterns(ADMIN, ADMIN + "/**");
	}

}
