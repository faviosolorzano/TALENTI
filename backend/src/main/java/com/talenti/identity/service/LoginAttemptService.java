package com.talenti.identity.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.talenti.identity.domain.AppUser;
import com.talenti.identity.domain.AppUserRepository;
import com.talenti.identity.domain.LockState;
import com.talenti.identity.domain.LoginAttemptPolicy;

/**
 * Guarda el resultado de los intentos de ingreso (contador y bloqueo, por cuenta). Cada llamada es su propia
 * transacción corta que bloquea la fila del usuario ({@code SELECT ... FOR UPDATE}): ni los intentos simultáneos
 * ni el error que se responde después deshacen o saltan el conteo. Ninguna transacción dura lo que dura el hash.
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

	/**
	 * Reserva un intento ANTES de comparar la contraseña. Devuelve {@code false} si no se puede comprobar
	 * (cuenta bloqueada o intentos agotados); entonces no se compara nada.
	 */
	@Transactional
	public boolean reserveAttempt(UUID userId) {
		Optional<AppUser> found = users.findByIdForUpdate(userId);
		if (found.isEmpty()) {
			return false;
		}
		AppUser user = found.get();
		Instant now = clock.instant();
		Optional<LockState> reserved = policy.reserve(user.lockState(), now);
		reserved.ifPresent(state -> user.applyLockState(state, now));
		return reserved.isPresent();
	}

	/** La contraseña comparada era errónea: si se completó el máximo de intentos, bloquea la cuenta. */
	@Transactional
	public void recordFailure(UUID userId) {
		users.findByIdForUpdate(userId).ifPresent(user -> {
			Instant now = clock.instant();
			LockState before = user.lockState();
			LockState after = policy.afterFailure(before, now);
			if (!after.equals(before)) {
				user.applyLockState(after, now);
				// Sin correo: solo el identificador interno de la cuenta.
				log.info("Cuenta bloqueada temporalmente por intentos fallidos (usuario {})", userId);
			}
		});
	}

	/** Ingreso correcto: reinicia el contador, pero nunca borra un bloqueo vigente. */
	@Transactional
	public void recordSuccess(UUID userId) {
		users.findByIdForUpdate(userId).ifPresent(user -> {
			Instant now = clock.instant();
			LockState before = user.lockState();
			LockState after = policy.afterSuccess(before, now);
			if (!after.equals(before)) {
				user.applyLockState(after, now);
			}
		});
	}

}
