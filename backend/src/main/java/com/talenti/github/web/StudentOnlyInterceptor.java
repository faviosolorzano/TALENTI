package com.talenti.github.web;

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
 * Autorización por rol de {@code /github} (013-23, T1): solo un estudiante; cualquier otra cosa recibe 403
 * {@code access-denied}. Falla cerrado. Marca la respuesta como privada ({@code Cache-Control: no-store}), rechaza
 * cuerpos desmedidos y aplica el límite general por persona. Corre después de la cadena de seguridad: sin sesión ya
 * se respondió 401, y sin token CSRF, 403.
 */
class StudentOnlyInterceptor implements HandlerInterceptor {

	/** Los cuerpos de /github son listas cortas de ids y decisiones. */
	static final long MAX_BODY_BYTES = 16 * 1024;

	private final PersonRateLimiter limiter;

	StudentOnlyInterceptor(PersonRateLimiter limiter) {
		this.limiter = limiter;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication != null && authentication.getPrincipal() instanceof CurrentUser user
				&& user.role() == Role.STUDENT)) {
			throw new ApiException(HttpStatus.FORBIDDEN, "access-denied");
		}
		if (request.getContentLengthLong() > MAX_BODY_BYTES) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "payload-too-large");
		}
		limiter.check(user.id());
		return true;
	}

}
