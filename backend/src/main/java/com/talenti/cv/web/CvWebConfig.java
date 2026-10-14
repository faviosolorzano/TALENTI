package com.talenti.cv.web;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.talenti.cv.config.CvProperties;

/**
 * Todo {@code /cv} exige sesión (lo hace la cadena de seguridad, 001-13; no se declara en {@code PublicRoutes}) y,
 * además, rol de estudiante y respuestas privadas: lo aplica un interceptor a TODA la ruta, así una ruta nueva no
 * puede olvidarse de ello.
 */
@Configuration(proxyBeanMethods = false)
class CvWebConfig implements WebMvcConfigurer {

	static final String API = "/api/v1";

	static final String CV = API + "/cv";

	/** Margen del cuerpo multipart sobre el tamaño del archivo (cabeceras de cada parte). */
	private static final long MULTIPART_OVERHEAD = 1024L * 1024;

	@Bean
	FilterRegistrationBean<CvUploadSizeFilter> cvUploadSizeFilter(CvProperties properties,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
		FilterRegistrationBean<CvUploadSizeFilter> registration = new FilterRegistrationBean<>(
				new CvUploadSizeFilter(properties.maxSize().toBytes() + MULTIPART_OVERHEAD, exceptionResolver));
		registration.setName("cvUploadSizeFilter");
		registration.addUrlPatterns(CV + "/analyses");
		return registration;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new CvAccessInterceptor()).addPathPatterns(CV, CV + "/**");
	}

}
