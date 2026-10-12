package com.talenti.identity.service;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.talenti.identity.CurrentUser;
import com.talenti.identity.Role;
import com.talenti.identity.domain.AppUser;
import com.talenti.identity.domain.AppUserRepository;
import com.talenti.identity.domain.EmailPolicy;
import com.talenti.identity.domain.LoginAttemptPolicy;
import com.talenti.identity.domain.PasswordPolicy;

/**
 * Ingreso (RF-03, RF-04) y consulta del usuario actual. Toda falla de credenciales (correo inexistente,
 * contraseña errónea o cuenta bloqueada) responde exactamente lo mismo (001-09, 001-10).
 */
@Service
public class AuthService {

	private final AppUserRepository users;

	private final LoginAttemptService attempts;

	private final LoginAttemptPolicy attemptPolicy;

	private final ConsentService consents;

	private final PasswordEncoder passwordEncoder;

	private final Clock clock;

	/** Hash de una contraseña al azar: se compara cuando no hay nada real que comparar, para gastar el mismo tiempo. */
	private final String decoyHash;

	AuthService(AppUserRepository users, LoginAttemptService attempts, LoginAttemptPolicy attemptPolicy,
			ConsentService consents, PasswordEncoder passwordEncoder, Clock clock) {
		this.users = users;
		this.attempts = attempts;
		this.attemptPolicy = attemptPolicy;
		this.consents = consents;
		this.passwordEncoder = passwordEncoder;
		this.clock = clock;
		this.decoyHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	/** El usuario y su estado de aceptación de los documentos legales vigentes (001-29). */
	public record Account(UUID id, String email, Role role, boolean legalAcceptancePending) {
	}

	public CurrentUser login(String rawEmail, String password) {
		String email = EmailPolicy.normalize(rawEmail);
		Optional<AppUser> found = users.findByEmail(email);
		if (found.isEmpty()) {
			spendHashingTime(password);
			throw new InvalidCredentialsException();
		}
		AppUser user = found.get();
		if (attemptPolicy.isLocked(user.lockState(), clock.instant())) {
			// Aun con la contraseña correcta se responde igual; el intento no extiende el bloqueo.
			spendHashingTime(password);
			throw new InvalidCredentialsException();
		}
		if (!matches(password, user.getPasswordHash())) {
			attempts.recordFailure(user.getId());
			throw new InvalidCredentialsException();
		}
		if (user.getFailedLoginCount() > 0 || user.getLockedUntil() != null) {
			attempts.recordSuccess(user.getId());
		}
		return new CurrentUser(user.getId(), user.getEmail(), user.getRole());
	}

	/** Datos frescos del usuario de la sesión. Sin cuenta (por ejemplo, ya borrada) es como no tener sesión. */
	public Optional<Account> account(UUID userId) {
		return users.findById(userId).map(user -> new Account(user.getId(), user.getEmail(), user.getRole(),
				consents.isAcceptancePending(user.getId())));
	}

	private boolean matches(String password, String hash) {
		if (PasswordPolicy.exceedsBcryptLimit(password)) {
			// Ninguna contraseña válida es tan larga; BCrypt además se negaría a compararla.
			spendHashingTime("x");
			return false;
		}
		return passwordEncoder.matches(password, hash);
	}

	private void spendHashingTime(String password) {
		String candidate = PasswordPolicy.exceedsBcryptLimit(password) ? "x" : password;
		passwordEncoder.matches(candidate, decoyHash);
	}

}
