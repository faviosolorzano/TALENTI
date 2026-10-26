package com.talenti.shared.events;

import java.util.UUID;

/**
 * Hecho del dominio: una postulación se retiró. Lo publica el módulo application dentro de la misma transacción del
 * retiro; quien lo escuche (por ejemplo practice, para cerrar una entrevista en curso, spec 012 sección 4) no hace
 * que application dependa de él. Solo lleva ids.
 */
public record ApplicationWithdrawn(UUID applicationId, UUID userId) {
}
