package com.talenti.practice.domain;

/** Estados de una sesión con DORA (012-14). */
public enum SessionStatus {

	CREATED,
	IN_PROGRESS,
	/** Terminó o la persona la cerró. */
	COMPLETED,
	/** Sin actividad por {@code inactivity-timeout}, o se retiró la postulación de la entrevista. */
	ABANDONED,
	/** Falla de la plataforma (por ejemplo, un reinicio del servidor). No se atribuye a la persona (principio 4). */
	FAILED;

	/** Abierta: todavía se puede conversar. Solo una por persona. */
	public boolean isOpen() {
		return this == CREATED || this == IN_PROGRESS;
	}

}
