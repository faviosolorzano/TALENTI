package com.talenti.application.web;

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
 * Autorización por rol de {@code /applications} (008-10, T1): solo un estudiante; cualquier otra cosa recibe 403
 * {@code access-denied}. Falla cerrado. Marca la respuesta como privada ({@code Cache-Control: no-store}) y rechaza
 * cuerpos desmedidos (el cuerpo es solo un id). Corre después de la cadena de seguridad: sin sesión ya se respondió 401,
 * y sin token CSRF, 403.
 */
class StudentOnlyInterceptor implements HandlerInterceptor {

	private static final long MAX_BODY_BYTES = 4 * 1024;

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
