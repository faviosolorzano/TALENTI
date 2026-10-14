package com.talenti.cv.web;

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
 * Autorización por rol de {@code /cv/**} (009-17): solo un estudiante; cualquier otra cosa (empresa, administrador, o
 * una sesión cuyo usuario no se reconoce) recibe 403 {@code access-denied}. Falla cerrado. Marca toda respuesta como
 * privada ({@code Cache-Control: no-store}) y rechaza cuerpos JSON desmedidos. Se ejecuta después de la cadena de
 * seguridad: sin sesión ya se respondió 401, y sin token CSRF, 403.
 */
class CvAccessInterceptor implements HandlerInterceptor {

	/** Las decisiones de confirmación caben de sobra en 256 KB. */
	static final long MAX_JSON_BYTES = 256 * 1024;

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		boolean student = authentication != null && authentication.getPrincipal() instanceof CurrentUser user
				&& user.role() == Role.STUDENT;
		if (!student) {
			throw new ApiException(HttpStatus.FORBIDDEN, "access-denied");
		}
		String contentType = request.getContentType();
		boolean multipart = contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("multipart/");
		if (!multipart && request.getContentLengthLong() > MAX_JSON_BYTES) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large");
		}
		return true;
	}

}
