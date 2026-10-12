package com.talenti.identity.web;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

import com.talenti.identity.CurrentUser;

/**
 * Abre y cierra la sesión de un usuario ya verificado:
 * <ul>
 * <li>Al abrirla cambia el identificador de la sesión (fijación de sesión, 001-08), guarda el usuario en el
 * contexto de seguridad y renueva el token CSRF.</li>
 * <li>Al cerrarla invalida la sesión en el servidor, borra la cookie (001-12) y entrega un token CSRF nuevo,
 * así la interfaz puede seguir enviando formularios (por ejemplo, ingresar de nuevo).</li>
 * </ul>
 */
@Component
class SessionEstablisher {

	private final SessionAuthenticationStrategy sessionStrategy;

	private final SecurityContextRepository securityContextRepository;

	private final CsrfTokenRepository csrfTokenRepository;

	private final String sessionCookieName;

	private final boolean secureCookies;

	SessionEstablisher(SessionAuthenticationStrategy sessionStrategy,
			SecurityContextRepository securityContextRepository, CsrfTokenRepository csrfTokenRepository,
			@Value("${server.servlet.session.cookie.name:JSESSIONID}") String sessionCookieName,
			@Value("${talenti.security.secure-cookies}") boolean secureCookies) {
		this.sessionStrategy = sessionStrategy;
		this.securityContextRepository = securityContextRepository;
		this.csrfTokenRepository = csrfTokenRepository;
		this.sessionCookieName = sessionCookieName;
		this.secureCookies = secureCookies;
	}

	void open(CurrentUser user, HttpServletRequest request, HttpServletResponse response) {
		List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(user.role().authority()));
		Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(user, null, authorities);
		sessionStrategy.onAuthentication(authentication, request, response);
		SecurityContext context = SecurityContextHolder.getContextHolderStrategy().createEmptyContext();
		context.setAuthentication(authentication);
		SecurityContextHolder.getContextHolderStrategy().setContext(context);
		securityContextRepository.saveContext(context, request, response);
		rotateCsrfToken(request, response);
	}

	void close(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
		new SecurityContextLogoutHandler().logout(request, response, authentication);
		ResponseCookie expired = ResponseCookie.from(sessionCookieName, "")
				.path(cookiePath(request))
				.maxAge(0)
				.httpOnly(true)
				.secure(secureCookies)
				.sameSite("Lax")
				.build();
		response.addHeader(HttpHeaders.SET_COOKIE, expired.toString());
		rotateCsrfToken(request, response);
	}

	private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
		CsrfToken fresh = csrfTokenRepository.generateToken(request);
		csrfTokenRepository.saveToken(fresh, request, response);
	}

	private static String cookiePath(HttpServletRequest request) {
		String contextPath = request.getContextPath();
		return contextPath == null || contextPath.isEmpty() ? "/" : contextPath;
	}

}
