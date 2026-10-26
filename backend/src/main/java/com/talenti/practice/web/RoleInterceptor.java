package com.talenti.practice.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import com.talenti.identity.CurrentUser;
import com.talenti.identity.Role;
import com.talenti.shared.web.ApiException;

/**
 * Autorización por rol de las rutas de DORA (012-32): solo el rol indicado; cualquier otro recibe 403
 * {@code access-denied}. Falla cerrado. Marca la respuesta como privada ({@code Cache-Control: no-store}) y rechaza
 * cuerpos JSON desmedidos (el audio va por multipart y lo limita {@link AudioSizeFilter}). Corre después de la cadena de
 * seguridad: sin sesión ya se respondió 401 y sin token CSRF, 403.
 */
class RoleInterceptor implements HandlerInterceptor {

	private static final long MAX_JSON_BYTES = 64 * 1024;

	private final Role role;

	RoleInterceptor(Role role) {
		this.role = role;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		boolean allowed = authentication != null && authentication.getPrincipal() instanceof CurrentUser user
				&& user.role() == role;
		if (!allowed) {
			throw new ApiException(HttpStatus.FORBIDDEN, "access-denied");
		}
		String type = request.getContentType();
		boolean multipart = type != null && type.toLowerCase(java.util.Locale.ROOT).startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
		if (!multipart && request.getContentLengthLong() > MAX_JSON_BYTES) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large");
		}
		return true;
	}

}
