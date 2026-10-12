package com.talenti.shared.audit;

import java.util.Map;
import java.util.UUID;

/**
 * Un hecho que queda en el registro de auditoría (RF-46). Describe qué pasó; nunca califica (principio 6).
 *
 * @param type        tipo del evento, por ejemplo {@code USER_REGISTERED}
 * @param actorUserId quién lo hizo (sin clave foránea: el registro sobrevive a la cuenta, RN-12)
 * @param subjectType tipo del objeto afectado, por ejemplo {@code app_user}; puede ser nulo
 * @param subjectId   identificador del objeto afectado; puede ser nulo
 * @param details     detalles técnicos. <strong>Nunca datos personales</strong> (ni correo ni contraseña)
 */
public record AuditEvent(String type, UUID actorUserId, String subjectType, UUID subjectId,
		Map<String, Object> details) {

	public AuditEvent {
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("El tipo del evento es obligatorio");
		}
		details = details == null ? Map.of() : Map.copyOf(details);
	}

}
