package com.talenti.vacancy.web;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * Caché de las rutas públicas de solo lectura (004-05): 60 segundos y {@code public}. Las respuestas de error no
 * pasan por aquí, así que conservan el {@code no-store} que Spring Security escribe por defecto; tampoco las demás
 * rutas de la API. El {@code ETag} lo agrega un filtro registrado solo para estas rutas (VacancyWebConfig).
 */
final class PublicCache {

	static final String CACHE_CONTROL = "public, max-age=60";

	private PublicCache() {
	}

	static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL).body(body);
	}

}
