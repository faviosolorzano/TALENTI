package com.talenti.identity.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.talenti.identity.CurrentUser;
import com.talenti.identity.domain.AppUser;
import com.talenti.identity.domain.AppUserRepository;
import com.talenti.identity.domain.EmailPolicy;
import com.talenti.identity.domain.PasswordPolicy;
import com.talenti.shared.audit.AuditEvent;
import com.talenti.shared.audit.AuditRecorder;
import com.talenti.shared.web.FieldViolation;
import com.talenti.shared.web.ValidationFailedException;

/**
 * Registro de un estudiante (RF-01, RF-02). El usuario, sus dos permisos y la auditoría se guardan en
 * <strong>una sola transacción</strong>: si algo falla, no queda nada (001-06).
 */
@Service
public class RegistrationService {

	public static final String USER_REGISTERED = "USER_REGISTERED";

	/** Nombre de la restricción única del correo (migración V2); sirve para distinguir esa violación. */
	private static final String EMAIL_UNIQUE_CONSTRAINT = "uq_app_user_email";

	private final AppUserRepository users;

	private final ConsentService consents;

	private final LegalDocumentService legal;

	private final PasswordPolicy passwordPolicy;

	private final PasswordEncoder passwordEncoder;

	private final AuditRecorder audit;

	private final TransactionTemplate transaction;

	private final Clock clock;

	RegistrationService(AppUserRepository users, ConsentService consents, LegalDocumentService legal,
			PasswordPolicy passwordPolicy, PasswordEncoder passwordEncoder, AuditRecorder audit,
			TransactionTemplate transaction, Clock clock) {
		this.users = users;
		this.consents = consents;
		this.legal = legal;
		this.passwordPolicy = passwordPolicy;
		this.passwordEncoder = passwordEncoder;
		this.audit = audit;
		this.transaction = transaction;
		this.clock = clock;
	}

	/** Datos del registro. No incluye el rol: el registro público siempre crea un {@code STUDENT} (001-07). */
	public record Command(String email, String password, Boolean legalAccepted, String legalVersion) {

		/** La contraseña no debe aparecer en ningún registro ni traza. */
		@Override
		public String toString() {
			return "RegistrationService.Command[...]";
		}

	}

	public CurrentUser register(Command command) {
		String email = EmailPolicy.normalize(command.email());
		validate(command, email);
		legal.requireCurrentVersion(command.legalVersion());
		if (users.existsByEmail(email)) {
			throw new EmailAlreadyRegisteredException();
		}
		// El hash (lento a propósito) se calcula fuera de la transacción.
		String passwordHash = passwordEncoder.encode(command.password());
		try {
			return transaction.execute(status -> create(email, passwordHash, command.legalVersion()));
		}
		catch (DataIntegrityViolationException e) {
			// Dos registros simultáneos con el mismo correo: la restricción única deja pasar solo uno.
			if (isEmailConflict(e)) {
				throw new EmailAlreadyRegisteredException();
			}
			throw e;
		}
	}

	private CurrentUser create(String email, String passwordHash, String legalVersion) {
		AppUser user = users.saveAndFlush(AppUser.newStudent(email, passwordHash, clock.instant()));
		consents.acceptAccountDocuments(user.getId(), legalVersion);
		audit.record(new AuditEvent(USER_REGISTERED, user.getId(), "app_user", user.getId(),
				Map.of("role", user.getRole().name())));
		return new CurrentUser(user.getId(), user.getEmail(), user.getRole());
	}

	private void validate(Command command, String email) {
		List<FieldViolation> violations = new ArrayList<>();
		EmailPolicy.check(email).ifPresent(violations::add);
		violations.addAll(passwordPolicy.check(command.password(), email));
		violations.addAll(legal.validateAcceptance(command.legalAccepted(), command.legalVersion()));
		if (!violations.isEmpty()) {
			throw new ValidationFailedException(violations);
		}
	}

	private static boolean isEmailConflict(DataIntegrityViolationException e) {
		// Solo se busca el nombre de la restricción; el mensaje (que trae el correo) nunca se registra.
		for (Throwable cause = e; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			String message = cause.getMessage();
			if (message != null && message.contains(EMAIL_UNIQUE_CONSTRAINT)) {
				return true;
			}
		}
		return false;
	}

}
