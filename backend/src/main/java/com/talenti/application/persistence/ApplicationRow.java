package com.talenti.application.persistence;

import java.time.Instant;
import java.util.UUID;

import com.talenti.application.domain.ApplicationStatus;

/** Una fila de {@code application}. */
public record ApplicationRow(UUID id, UUID userId, UUID vacancyId, ApplicationStatus status, Instant submittedAt,
		Instant withdrawnAt) {
}
