package com.talenti.application.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.talenti.application.domain.ApplicationStatus;

/**
 * Único lugar del módulo que toca {@code application} y {@code application_stage_event}. Toda consulta por persona
 * filtra por {@code user_id}: una postulación ajena es igual a una inexistente (008-08).
 */
@Repository
public class ApplicationRepository {

	private static final String COLUMNS = "id, user_id, vacancy_id, status, submitted_at, withdrawn_at";

	private static final String VACANCY_FILTER = " AND (CAST(:vacancyId AS uuid) IS NULL OR vacancy_id = CAST(:vacancyId AS uuid))";

	/** Un cambio de estado del historial. */
	public record Stage(ApplicationStatus status, Instant at) {
	}

	private final JdbcClient jdbc;

	ApplicationRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static ApplicationRow row(ResultSet rs, int n) throws SQLException {
		OffsetDateTime withdrawn = rs.getObject("withdrawn_at", OffsetDateTime.class);
		return new ApplicationRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getObject("vacancy_id", UUID.class), ApplicationStatus.valueOf(rs.getString("status")),
				rs.getObject("submitted_at", OffsetDateTime.class).toInstant(),
				withdrawn == null ? null : withdrawn.toInstant());
	}

	/**
	 * Crea la postulación en {@code SUBMITTED} con su primer evento.
	 *
	 * @throws org.springframework.dao.DuplicateKeyException si ya hay una activa a esa vacante
	 */
	public void insert(UUID id, UUID userId, UUID vacancyId, Instant now) {
		jdbc.sql("""
				INSERT INTO application (id, user_id, vacancy_id, status, submitted_at, created_at, updated_at)
				VALUES (:id, :userId, :vacancyId, 'SUBMITTED', :now, :now, :now)""")
				.param("id", id).param("userId", userId).param("vacancyId", vacancyId).param("now", utc(now)).update();
		addEvent(id, null, ApplicationStatus.SUBMITTED, now);
	}

	/** Retira la postulación si sigue enviada. Devuelve falso si ya no lo estaba (otro retiro llegó antes). */
	public boolean withdraw(UUID id, UUID userId, Instant now) {
		int updated = jdbc.sql("""
				UPDATE application SET status = 'WITHDRAWN', withdrawn_at = :now, updated_at = :now
				WHERE id = :id AND user_id = :userId AND status = 'SUBMITTED'""")
				.param("id", id).param("userId", userId).param("now", utc(now)).update();
		if (updated == 1) {
			addEvent(id, ApplicationStatus.SUBMITTED, ApplicationStatus.WITHDRAWN, now);
		}
		return updated == 1;
	}

	private void addEvent(UUID applicationId, ApplicationStatus from, ApplicationStatus to, Instant at) {
		jdbc.sql("""
				INSERT INTO application_stage_event (id, application_id, from_status, to_status, occurred_at)
				VALUES (:id, :applicationId, :from, :to, :at)""")
				.param("id", UUID.randomUUID()).param("applicationId", applicationId)
				.param("from", from == null ? null : from.name()).param("to", to.name()).param("at", utc(at)).update();
	}

	public Optional<ApplicationRow> findOwned(UUID id, UUID userId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM application WHERE id = :id AND user_id = :userId")
				.param("id", id).param("userId", userId).query(ApplicationRepository::row).optional();
	}

	public List<Stage> history(UUID applicationId) {
		return jdbc.sql("SELECT to_status, occurred_at FROM application_stage_event WHERE application_id = :id "
				+ "ORDER BY occurred_at, (from_status IS NOT NULL)").param("id", applicationId)
				.query((rs, n) -> new Stage(ApplicationStatus.valueOf(rs.getString("to_status")),
						rs.getObject("occurred_at", OffsetDateTime.class).toInstant()))
				.list();
	}

	/** Postulaciones de la persona, de la más reciente a la más antigua; con filtro opcional por vacante. */
	public List<ApplicationRow> list(UUID userId, UUID vacancyId, int limit, int offset) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM application WHERE user_id = :userId" + VACANCY_FILTER
				+ " ORDER BY submitted_at DESC, id DESC LIMIT :limit OFFSET :offset")
				.param("userId", userId).param("vacancyId", vacancyId).param("limit", limit).param("offset", offset)
				.query(ApplicationRepository::row).list();
	}

	public int count(UUID userId, UUID vacancyId) {
		return jdbc.sql("SELECT count(*) FROM application WHERE user_id = :userId" + VACANCY_FILTER)
				.param("userId", userId).param("vacancyId", vacancyId).query(Integer.class).single();
	}

	/** Cantidad de postulaciones activas (no retiradas ni vencidas). */
	public int countActive(UUID userId) {
		return jdbc.sql("SELECT count(*) FROM application WHERE user_id = :userId "
				+ "AND status NOT IN ('WITHDRAWN', 'EXPIRED')").param("userId", userId).query(Integer.class).single();
	}

	public int countWithdrawn(UUID userId) {
		return jdbc.sql("SELECT count(*) FROM application WHERE user_id = :userId AND status = 'WITHDRAWN'")
				.param("userId", userId).query(Integer.class).single();
	}

	public Optional<ApplicationRow> latest(UUID userId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM application WHERE user_id = :userId "
				+ "ORDER BY submitted_at DESC, id DESC LIMIT 1").param("userId", userId)
				.query(ApplicationRepository::row).optional();
	}

}
