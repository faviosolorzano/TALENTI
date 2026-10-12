package com.talenti.shared;

import java.nio.charset.StandardCharsets;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.savedrequest.NullRequestCache;

/**
 * Seguridad mínima de la fundación (Fase 0): solo la salud es pública y todo lo demás exige sesión.
 * La spec 001 (identidad) agrega el inicio de sesión, la cookie, CSRF y la autorización por rol y objeto.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

	private static final String UNAUTHENTICATED_BODY = """
			{"type":"about:blank","title":"No autenticado","status":401,\
			"detail":"Se requiere una sesión activa para acceder a este recurso."}""";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http
				.authorizeHttpRequests(requests -> requests
						// El error ya pasó por la seguridad en la petición original; sin esto un 500 se vería como 401.
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers("/actuator/health/**").permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(handling -> handling.authenticationEntryPoint(unauthorizedEntryPoint()))
				// Una petición anónima no debe crear sesión ni recibir cookie (el 401 sale sin Set-Cookie).
				// La spec 001 pasará a IF_REQUIRED cuando exista el inicio de sesión.
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.NEVER))
				.requestCache(cache -> cache.requestCache(new NullRequestCache()))
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				.logout(logout -> logout.disable());
		return http.build();
	}

	/**
	 * Sin usuarios: evita que Spring Boot genere una contraseña aleatoria y la escriba en el registro.
	 * La spec 001 la reemplaza por usuarios reales guardados en la base de datos.
	 */
	@Bean
	UserDetailsService userDetailsService() {
		return new InMemoryUserDetailsManager();
	}

	private AuthenticationEntryPoint unauthorizedEntryPoint() {
		return (request, response, authException) -> {
			response.setStatus(HttpStatus.UNAUTHORIZED.value());
			response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			response.setCharacterEncoding(StandardCharsets.UTF_8.name());
			response.getWriter().write(UNAUTHENTICATED_BODY);
		};
	}

}
