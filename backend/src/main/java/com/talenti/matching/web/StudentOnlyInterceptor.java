package com.talenti.matching.web;

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
 * Autorización por rol de {@code /compatibility} (007-14): solo un estudiante; cualquier otra cosa recibe 403
 * {@code access-denied} (falla cerrado). Marca toda respuesta como privada ({@code Cache-Control: no-store}): el
 * resultado depende del perfil de la persona. Se ejecuta después de la cadena de seguridad: sin sesión ya se
 * respondió 401.
 */
class StudentOnlyInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		boolean student = authentication != null && authentication.getPrincipal() instanceof CurrentUser user
				&& user.role() == Role.STUDENT;
		if (!student) {
			throw new ApiException(HttpStatus.FORBIDDEN, "access-denied");
		}
		return true;
	}

}
