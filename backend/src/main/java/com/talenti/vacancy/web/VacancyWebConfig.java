package com.talenti.vacancy.web;

import java.util.List;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

import com.talenti.shared.security.PublicRoute;
import com.talenti.shared.security.PublicRoutes;

/** Rutas públicas de vacancy (solo {@code GET}) y {@code ETag} únicamente para ellas. */
@Configuration(proxyBeanMethods = false)
class VacancyWebConfig {

	static final String API = "/api/v1";

	/**
	 * Todo lo demás de vacancy sigue exigiendo sesión (001-13), y un POST, PUT o DELETE sin sesión recibe 401
	 * (004-14). Las rutas fijas ({@code featured}, {@code filters}) no son identificadores.
	 */
	@Bean
	PublicRoutes vacancyPublicRoutes() {
		return () -> List.of(
				PublicRoute.get(API + "/vacancies"),
				PublicRoute.get(API + "/vacancies/featured"),
				PublicRoute.get(API + "/vacancies/filters"),
				PublicRoute.get(API + "/vacancies/*"),
				PublicRoute.get(API + "/companies/featured"));
	}

	/**
	 * Calcula el {@code ETag} de la respuesta y contesta 304 si el visitante ya la tiene. Solo en las cinco rutas
	 * públicas: el resto de la API no se toca. Solo actúa sobre respuestas 2xx de {@code GET}.
	 */
	@Bean
	FilterRegistrationBean<ShallowEtagHeaderFilter> vacancyEtagFilter() {
		FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(
				new ShallowEtagHeaderFilter());
		registration.setName("vacancyEtagFilter");
		registration.addUrlPatterns(API + "/vacancies", API + "/vacancies/*", API + "/companies/featured");
		return registration;
	}

}
