package com.talenti.shared.security;

import org.springframework.http.HttpMethod;

/** Una ruta que no exige sesión. CSRF sigue exigido en los métodos que cambian datos. */
public record PublicRoute(HttpMethod method, String pattern) {

	public static PublicRoute get(String pattern) {
		return new PublicRoute(HttpMethod.GET, pattern);
	}

	public static PublicRoute post(String pattern) {
		return new PublicRoute(HttpMethod.POST, pattern);
	}

}
