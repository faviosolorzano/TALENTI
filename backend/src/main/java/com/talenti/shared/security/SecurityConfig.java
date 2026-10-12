package com.talenti.shared.security;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.talenti.shared.web.ApiException;

/**
 * Seguridad de la aplicación (spec 001):
 * <ul>
 * <li>Todo exige sesión salvo la salud y las rutas que los módulos declaran en {@link PublicRoutes}.</li>
 * <li>Sesión por cookie ({@code IF_REQUIRED}): una petición anónima no crea sesión ni recibe cookie.</li>
 * <li>CSRF para aplicaciones de una sola página: la cookie {@code XSRF-TOKEN} la lee el frontend y
 * devuelve el valor en la cabecera {@code X-XSRF-TOKEN}.</li>
 * <li>Los errores 401 y 403 salen como Problem Details por el mismo manejador que el resto de la API.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectProvider<PublicRoutes> publicRoutes,
			CsrfTokenRepository csrfTokenRepository, SecurityContextRepository securityContextRepository,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
		http
				// Cookie XSRF-TOKEN legible por el frontend (con SameSite y Secure configurados) y manejador
				// para aplicaciones de una sola página con carga diferida (ver SpaCsrfTokenRequestHandler).
				.csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository)
						.csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
				.authorizeHttpRequests(requests -> {
					// El error ya pasó por la seguridad en la petición original; sin esto un 500 se vería como 401.
					requests.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll();
					requests.requestMatchers("/actuator/health/**").permitAll();
					publicRoutes.orderedStream().flatMap(routes -> routes.publicRoutes().stream())
							.forEach(route -> requests.requestMatchers(route.method(), route.pattern()).permitAll());
					requests.anyRequest().authenticated();
				})
				.exceptionHandling(handling -> handling
						.authenticationEntryPoint(new ApiAuthenticationEntryPoint(exceptionResolver))
						.accessDeniedHandler(new ApiAccessDeniedHandler(exceptionResolver)))
				.securityContext(context -> context.securityContextRepository(securityContextRepository))
				// IF_REQUIRED: la sesión se crea solo al ingresar. Una petición anónima no la crea (la fase 0
				// lo exigía con NEVER; aquí lo garantizan NullRequestCache y que el token CSRF vive en una cookie).
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
				.requestCache(cache -> cache.requestCache(new NullRequestCache()))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable());
		return http.build();
	}

	/**
	 * Token CSRF en la cookie {@code XSRF-TOKEN}, legible por JavaScript (no HttpOnly), con {@code SameSite=Lax}
	 * y {@code Secure} según la misma configuración que la cookie de sesión.
	 */
	@Bean
	CsrfTokenRepository csrfTokenRepository(@Value("${talenti.security.secure-cookies}") boolean secureCookies) {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookieCustomizer(cookie -> cookie.secure(secureCookies).sameSite("Lax"));
		return repository;
	}

	@Bean
	SecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	/** Cambia el identificador de la sesión al ingresar (protección contra fijación de sesión, 001-08). */
	@Bean
	SessionAuthenticationStrategy sessionAuthenticationStrategy() {
		return new ChangeSessionIdAuthenticationStrategy();
	}

	/**
	 * Sin usuarios: el ingreso lo hace el módulo identity con su propio servicio. Evita que Spring Boot genere
	 * una contraseña aleatoria y la escriba en el registro.
	 */
	@Bean
	UserDetailsService userDetailsService() {
		return new InMemoryUserDetailsManager();
	}

	/** 401: sin sesión, o con una cookie de sesión que ya no es válida ({@code session-expired}, 001-14). */
	private record ApiAuthenticationEntryPoint(HandlerExceptionResolver resolver) implements AuthenticationEntryPoint {

		@Override
		public void commence(HttpServletRequest request, HttpServletResponse response,
				AuthenticationException authException) throws IOException {
			boolean sessionExpired = request.getRequestedSessionId() != null && !request.isRequestedSessionIdValid();
			resolve(resolver, request, response, HttpStatus.UNAUTHORIZED,
					sessionExpired ? "session-expired" : "unauthenticated");
		}

	}

	/** 403: token CSRF ausente o inválido ({@code csrf-invalid}, 001-15), u otro acceso denegado. */
	private record ApiAccessDeniedHandler(HandlerExceptionResolver resolver) implements AccessDeniedHandler {

		@Override
		public void handle(HttpServletRequest request, HttpServletResponse response,
				AccessDeniedException accessDeniedException) throws IOException {
			String code = accessDeniedException instanceof CsrfException ? "csrf-invalid" : "access-denied";
			resolve(resolver, request, response, HttpStatus.FORBIDDEN, code);
		}

	}

	private static void resolve(HandlerExceptionResolver resolver, HttpServletRequest request,
			HttpServletResponse response, HttpStatus status, String code) throws IOException {
		if (resolver.resolveException(request, response, null, new ApiException(status, code)) == null) {
			response.sendError(status.value());
		}
	}

}
