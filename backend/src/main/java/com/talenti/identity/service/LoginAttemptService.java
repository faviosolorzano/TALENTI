package com.talenti.identity.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.talenti.identity.domain.AppUserRepository;
import com.talenti.identity.domain.LockState;
import com.talenti.identity.domain.LoginAttemptPolicy;

/**
 * Guarda el resultado de los intentos de ingreso (contador y bloqueo, por cuenta). Cada llamada es su propia
 * transacción y bloquea la fila del usuario: ni los intentos simultáneos ni el error que se responde después
 * deshacen el conteo.
 */
@Service
public class LoginAttemptService {

	private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

	private final AppUserRepository users;

	private final LoginAttemptPolicy policy;

	private final Clock clock;

	LoginAttemptService(AppUserRepository users, LoginAttemptPolicy policy, Clock clock) {
		this.users = users;
		this.policy = policy;
		this.clock = clock;
	}

	@Transactional
	public void recordFailure(UUID userId) {
		users.findByIdForUpdate(userId).ifPresent(user -> {
			Instant now = clock.instant();
			LockState before = user.lockState();
			LockState after = policy.afterFailure(before, now);
			user.applyLockState(after, now);
			if (after.lockedUntil() != null && !after.equals(before) && !policy.isLocked(before, now)) {
				// Sin correo: solo el identificador interno de la cuenta.
				log.info("Cuenta bloqueada temporalmente por intentos fallidos (usuario {})", userId);
			}
		});
	}

	@Transactional
	public void recordSuccess(UUID userId) {
		users.findByIdForUpdate(userId).ifPresent(user -> user.applyLockState(policy.afterSuccess(), clock.instant()));
	}

}
