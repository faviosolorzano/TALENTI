package com.talenti.profile.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import com.talenti.identity.CurrentUser;
import com.talenti.identity.Role;
import com.talenti.shared.web.ApiException;

/**
 * Autorización por rol de {@code /profile} (006-04, T1): solo un estudiante. Cualquier otra cosa (empresa,
 * administrador, o una sesión cuyo usuario no se reconoce) recibe 403 {@code access-denied}. Falla cerrado.
 *
 * <p>También marca la respuesta como privada ({@code Cache-Control: no-store}) y rechaza cuerpos desmedidos antes de
 * leerlos. Se ejecuta después de la cadena de seguridad: sin sesión ya se respondió 401, y sin token CSRF, 403.
 */
class StudentOnlyInterceptor implements HandlerInterceptor {

	/** Un perfil completo cabe de sobra en 64 KB; más es un cuerpo desmedido (límite de subida). */
	static final long MAX_BODY_BYTES = 64 * 1024;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		boolean student = authentication != null && authentication.getPrincipal() instanceof CurrentUser user
				&& user.role() == Role.STUDENT;
		if (!student) {
			throw new ApiException(HttpStatus.FORBIDDEN, "access-denied");
		}
		if (request.getContentLengthLong() > MAX_BODY_BYTES) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large");
		}
		return true;
	}

}
