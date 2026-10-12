package com.talenti.identity.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.talenti.identity.CurrentUser;
import com.talenti.identity.service.AuthService;
import com.talenti.identity.service.RegistrationService;
import com.talenti.shared.web.ApiException;

/**
 * Registro, ingreso, cierre de sesión y usuario actual. El id del usuario siempre sale de la sesión:
 * ninguna ruta recibe el id de otra persona (001-16).
 */
@RestController
@RequestMapping(IdentityWebConfig.API + "/auth")
class AuthController {

	private final RegistrationService registration;

	private final AuthService auth;

	private final SessionEstablisher sessions;

	AuthController(RegistrationService registration, AuthService auth, SessionEstablisher sessions) {
		this.registration = registration;
		this.auth = auth;
		this.sessions = sessions;
	}

	/** Deja la cookie {@code XSRF-TOKEN}: el frontend la lee y la devuelve en {@code X-XSRF-TOKEN}. */
	@GetMapping("/csrf")
	ResponseEntity<Void> csrf(CsrfToken token) {
		// El token es diferido: pedir su valor es lo que hace que se genere y se escriba la cookie.
		token.getToken();
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/register")
	ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		CurrentUser user = registration.register(new RegistrationService.Command(body.email(), body.password(),
				body.legalAccepted(), body.legalVersion()));
		sessions.open(user, request, response);
		return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.of(user));
	}

	@PostMapping("/login")
	ResponseEntity<UserResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		CurrentUser user = auth.login(body.email(), body.password());
		sessions.open(user, request, response);
		return ResponseEntity.ok(UserResponse.of(user));
	}

	@PostMapping("/logout")
	ResponseEntity<Void> logout(Authentication authentication, HttpServletRequest request,
			HttpServletResponse response) {
		sessions.close(request, response, authentication);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me")
	ResponseEntity<MeResponse> me(@AuthenticationPrincipal CurrentUser current) {
		return auth.account(current.id())
				.map(account -> ResponseEntity.ok(MeResponse.of(account)))
				.orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated"));
	}

}
