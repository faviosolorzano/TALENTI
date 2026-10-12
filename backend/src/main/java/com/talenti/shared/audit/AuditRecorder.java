package com.talenti.shared.audit;

/**
 * Interfaz pública de la auditoría. Los demás módulos registran eventos por aquí y nunca escriben en la
 * tabla {@code audit_event} (principio 12).
 *
 * <p>El evento se escribe en la transacción que esté activa: si esa transacción se revierte, el evento
 * también (por ejemplo, el registro de una cuenta y su auditoría son atómicos, 001-06).
 */
public interface AuditRecorder {

	void record(AuditEvent event);

}
