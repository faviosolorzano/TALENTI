package com.talenti.shared.audit;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.talenti.shared.web.Correlation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Escribe en {@code audit_event} con la conexión de la transacción en curso. Interno del módulo shared. */
@Component
class JdbcAuditRecorder implements AuditRecorder {

	private static final String INSERT = """
			INSERT INTO audit_event (id, occurred_at, event_type, actor_user_id, subject_type, subject_id,
			                         correlation_id, details)
			VALUES (:id, :occurredAt, :eventType, :actorUserId, :subjectType, :subjectId, :correlationId,
			        CAST(:details AS jsonb))
			""";

	private final JdbcClient jdbc;

	private final JsonMapper jsonMapper;

	private final Clock clock;

	JdbcAuditRecorder(JdbcClient jdbc, JsonMapper jsonMapper, Clock clock) {
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
	}

	@Override
	public void record(AuditEvent event) {
		jdbc.sql(INSERT)
				.param("id", UUID.randomUUID())
				.param("occurredAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
				.param("eventType", event.type())
				.param("actorUserId", event.actorUserId())
				.param("subjectType", event.subjectType())
				.param("subjectId", event.subjectId())
				.param("correlationId", currentCorrelationId())
				.param("details", toJson(event))
				.update();
	}

	private String toJson(AuditEvent event) {
		try {
			return jsonMapper.writeValueAsString(event.details());
		}
		catch (JacksonException e) {
			throw new IllegalStateException("No se pudo serializar el detalle del evento de auditoría", e);
		}
	}

	private static UUID currentCorrelationId() {
		String value = MDC.get(Correlation.MDC_KEY);
		if (value == null) {
			return null;
		}
		try {
			return UUID.fromString(value);
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

}
