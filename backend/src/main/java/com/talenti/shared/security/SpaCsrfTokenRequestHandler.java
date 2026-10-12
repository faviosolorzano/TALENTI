package com.talenti.shared.security;

import java.util.function.Supplier;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

/**
 * Manejador de token CSRF para aplicaciones de una sola página (patrón de la documentación de Spring Security,
 * sección "Single-Page Applications"): el frontend lee la cookie {@code XSRF-TOKEN} y devuelve su valor tal cual
 * en la cabecera {@code X-XSRF-TOKEN}.
 *
 * <ul>
 * <li>Para entregar el token usa la protección contra BREACH ({@link XorCsrfTokenRequestAttributeHandler}).</li>
 * <li>Para leer el que llega en la cabecera usa el valor sin codificar, que es el de la cookie.</li>
 * <li>El token se carga <strong>de forma diferida</strong>: solo se genera y se escribe la cookie cuando alguien
 * lo pide ({@code GET /auth/csrf}). Así una petición anónima cualquiera no recibe ninguna cookie, que es lo que
 * exigía la fase 0 y lo que mantiene esta rebanada. (El atajo {@code csrf.spa()} del configurador desactiva esa
 * carga diferida y escribiría la cookie en todas las respuestas.)</li>
 * </ul>
 */
final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

	private final CsrfTokenRequestAttributeHandler plain = new CsrfTokenRequestAttributeHandler();

	private final CsrfTokenRequestAttributeHandler xor = new XorCsrfTokenRequestAttributeHandler();

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
		xor.handle(request, response, csrfToken);
	}

	@Override
	public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
		String header = request.getHeader(csrfToken.getHeaderName());
		return (StringUtils.hasText(header) ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
	}

}
