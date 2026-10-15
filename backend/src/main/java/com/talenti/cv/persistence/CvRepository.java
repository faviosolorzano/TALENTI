package com.talenti.cv.persistence;

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

import com.talenti.cv.SuggestionKind;
import com.talenti.cv.domain.SuggestionDraft;

import tools.jackson.databind.json.JsonMapper;

/**
 * Único lugar del módulo cv que toca {@code cv_analysis} y {@code cv_suggestion}. Ninguna consulta registra contenido.
 * Las consultas por persona SIEMPRE filtran por {@code user_id}: así un análisis ajeno y uno inexistente son lo mismo
 * (009-17).
 */
@Repository
public class CvRepository {

	private static final String COLUMNS = """
			id, user_id, status, failure_reason, file_size, file_sha256, reduced_text, input_truncated, level, reused,
			degraded, degraded_reason, adapter, adapter_version, rule_set_version, current_read_id, created_at,
			finished_at, expires_at""";

	private final JdbcClient jdbc;

	private final JsonMapper mapper;

	CvRepository(JdbcClient jdbc, JsonMapper mapper) {
		this.jdbc = jdbc;
		this.mapper = mapper;
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static AnalysisRecord analysis(ResultSet rs, int row) throws SQLException {
		return new AnalysisRecord(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getString("status"), rs.getString("failure_reason"), rs.getInt("file_size"),
				rs.getString("file_sha256"), rs.getString("reduced_text"), rs.getBoolean("input_truncated"),
				rs.getString("level"), rs.getBoolean("reused"), rs.getBoolean("degraded"),
				rs.getString("degraded_reason"), rs.getString("adapter"), rs.getString("adapter_version"),
				rs.getString("rule_set_version"), rs.getObject("current_read_id", UUID.class),
				instant(rs, "created_at"), instant(rs, "finished_at"), instant(rs, "expires_at"));
	}

	// ------------------------------------------------------------------------------------------ consultas

	/** El análisis abierto de la persona (PENDING, ANALYZING o READY), si hay. */
	public Optional<AnalysisRecord> findOpen(UUID userId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM cv_analysis WHERE user_id = :userId "
				+ "AND status IN ('PENDING', 'ANALYZING', 'READY')").param("userId", userId)
				.query(CvRepository::analysis).optional();
	}

	/** El análisis vigente de la persona: abierto, o el último que falló (para ofrecerle reintentar). */
	public Optional<AnalysisRecord> findCurrent(UUID userId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM cv_analysis WHERE user_id = :userId "
				+ "AND status IN ('PENDING', 'ANALYZING', 'READY', 'FAILED') ORDER BY created_at DESC LIMIT 1")
				.param("userId", userId).query(CvRepository::analysis).optional();
	}

	/** Un análisis de la persona (cualquier estado); vacío si no existe o es de otra persona. */
	public Optional<AnalysisRecord> findOwned(UUID id, UUID userId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM cv_analysis WHERE id = :id AND user_id = :userId")
				.param("id", id).param("userId", userId).query(CvRepository::analysis).optional();
	}

	/** El mismo, bloqueado hasta el final de la transacción (confirmar de una en una). */
	public Optional<AnalysisRecord> lockOwned(UUID id, UUID userId) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM cv_analysis WHERE id = :id AND user_id = :userId FOR UPDATE")
				.param("id", id).param("userId", userId).query(CvRepository::analysis).optional();
	}

	public Optional<AnalysisRecord> findForJob(UUID id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM cv_analysis WHERE id = :id").param("id", id)
				.query(CvRepository::analysis).optional();
	}

	/** Análisis nuevos de la persona desde el instante dado (límite de 24 horas, 009-07). */
	public int countCreatedSince(UUID userId, Instant since) {
		return jdbc.sql("SELECT count(*) FROM cv_analysis WHERE user_id = :userId AND created_at > :since")
				.param("userId", userId).param("since", utc(since)).query(Integer.class).single();
	}

	public Optional<Instant> oldestCreatedSince(UUID userId, Instant since) {
		return jdbc.sql("SELECT min(created_at) FROM cv_analysis WHERE user_id = :userId AND created_at > :since")
				.param("userId", userId).param("since", utc(since))
				.query((rs, row) -> instant(rs, "min")).optional().filter(java.util.Objects::nonNull);
	}

	/** Si la persona ya leyó con IA ese mismo archivo en un análisis ya cerrado, dentro de la ventana (009-26). */
	public boolean hasClosedEnhancedRead(UUID userId, String sha256, Instant since) {
		return jdbc.sql("""
				SELECT count(*) FROM cv_analysis
				WHERE user_id = :userId AND file_sha256 = :sha AND level = 'ENHANCED'
				  AND status IN ('CONFIRMED', 'DISCARDED', 'EXPIRED') AND created_at > :since""")
				.param("userId", userId).param("sha", sha256).param("since", utc(since)).query(Integer.class)
				.single() > 0;
	}

	public List<SuggestionRecord> suggestions(UUID analysisId) {
		return jdbc.sql("SELECT id, kind, field, payload, evidence, position FROM cv_suggestion "
				+ "WHERE analysis_id = :id ORDER BY position").param("id", analysisId).query((rs, row) -> {
					try {
						return new SuggestionRecord(rs.getObject("id", UUID.class),
								SuggestionKind.valueOf(rs.getString("kind")), rs.getString("field"),
								mapper.readTree(rs.getString("payload")), rs.getString("evidence"),
								rs.getInt("position"));
					}
					catch (tools.jackson.core.JacksonException e) {
						throw new SQLException("Sugerencia con un formato que no se puede leer");
					}
				}).list();
	}

	// ------------------------------------------------------------------------------------------ escrituras

	/**
	 * Crea el análisis en PENDING.
	 *
	 * @throws org.springframework.dao.DuplicateKeyException si la persona ya tiene uno abierto
	 */
	public void insert(UUID id, UUID userId, int fileSize, String sha256, String reducedText, boolean truncated,
			UUID readId, String adapter, String adapterVersion, String ruleSetVersion, boolean degraded,
			String degradedReason, Instant now, Instant expiresAt) {
		jdbc.sql("""
				INSERT INTO cv_analysis (id, user_id, status, file_size, file_sha256, reduced_text, input_truncated,
				                         current_read_id, adapter, adapter_version, rule_set_version, degraded,
				                         degraded_reason, created_at, expires_at)
				VALUES (:id, :userId, 'PENDING', :size, :sha, :text, :truncated, :readId, :adapter, :adapterVersion,
				        :ruleSetVersion, :degraded, :degradedReason, :now, :expires)""")
				.param("id", id).param("userId", userId).param("size", fileSize).param("sha", sha256)
				.param("text", reducedText).param("truncated", truncated).param("readId", readId)
				.param("adapter", adapter).param("adapterVersion", adapterVersion)
				.param("ruleSetVersion", ruleSetVersion).param("degraded", degraded)
				.param("degradedReason", degradedReason).param("now", utc(now)).param("expires", utc(expiresAt))
				.update();
	}

	/** Marca que se volvió a subir el mismo archivo y se devolvió este análisis (009-26). */
	public void markReused(UUID id) {
		jdbc.sql("UPDATE cv_analysis SET reused = true WHERE id = :id").param("id", id).update();
	}

	/**
	 * Pone el análisis a esperar una lectura nueva (READY o FAILED a PENDING). Devuelve 0 si ya no estaba en uno de esos
	 * estados (otra pulsación llegó antes).
	 *
	 * @throws org.springframework.dao.DuplicateKeyException si el análisis estaba FAILED y la persona ya abrió otro
	 */
	public int requestRead(UUID id, UUID userId, UUID readId, boolean degraded, String degradedReason) {
		return jdbc.sql("""
				UPDATE cv_analysis SET status = 'PENDING', current_read_id = :readId, failure_reason = NULL,
				       reused = false, degraded = :degraded, degraded_reason = :degradedReason
				WHERE id = :id AND user_id = :userId AND status IN ('READY', 'FAILED')""")
				.param("id", id).param("userId", userId).param("readId", readId).param("degraded", degraded)
				.param("degradedReason", degradedReason).update();
	}

	/** El trabajo toma la lectura: PENDING a ANALYZING. Devuelve 0 si otro ya la tomó o se descartó. */
	public int claim(UUID id, UUID readId) {
		return jdbc.sql("UPDATE cv_analysis SET status = 'ANALYZING' "
				+ "WHERE id = :id AND status = 'PENDING' AND current_read_id = :readId")
				.param("id", id).param("readId", readId).update();
	}

	/**
	 * Termina la lectura con éxito. Devuelve falso si el análisis ya no estaba esperando esta lectura (por ejemplo, se
	 * descartó mientras se leía): entonces no se guarda nada.
	 *
	 * @param drafts nulo para conservar las sugerencias anteriores
	 */
	public boolean complete(UUID id, UUID readId, List<SuggestionDraft> drafts, String level, String adapter,
			String adapterVersion, String ruleSetVersion, boolean degraded, String degradedReason,
			boolean inputTruncated) {
		int updated = jdbc.sql("""
				UPDATE cv_analysis SET status = 'READY', failure_reason = NULL, level = :level, adapter = :adapter,
				       adapter_version = :adapterVersion, rule_set_version = :ruleSetVersion, degraded = :degraded,
				       degraded_reason = :degradedReason, input_truncated = :truncated
				WHERE id = :id AND status = 'ANALYZING' AND current_read_id = :readId""")
				.param("id", id).param("readId", readId).param("level", level).param("adapter", adapter)
				.param("adapterVersion", adapterVersion).param("ruleSetVersion", ruleSetVersion)
				.param("degraded", degraded).param("degradedReason", degradedReason)
				.param("truncated", inputTruncated).update();
		if (updated == 0) {
			return false;
		}
		if (drafts != null) {
			replaceSuggestions(id, drafts);
		}
		return true;
	}

	/** Conserva las sugerencias anteriores cuando una lectura nueva falla: vuelve a READY y avisa que degradó. */
	public boolean keepPrevious(UUID id, UUID readId, String degradedReason) {
		return jdbc.sql("""
				UPDATE cv_analysis SET status = 'READY', failure_reason = NULL, degraded = true,
				       degraded_reason = :reason
				WHERE id = :id AND status = 'ANALYZING' AND current_read_id = :readId""")
				.param("id", id).param("readId", readId).param("reason", degradedReason).update() == 1;
	}

	private void replaceSuggestions(UUID analysisId, List<SuggestionDraft> drafts) {
		jdbc.sql("DELETE FROM cv_suggestion WHERE analysis_id = :id").param("id", analysisId).update();
		int position = 0;
		for (SuggestionDraft draft : drafts) {
			jdbc.sql("""
					INSERT INTO cv_suggestion (id, analysis_id, kind, field, payload, evidence, position)
					VALUES (:id, :analysisId, :kind, :field, CAST(:payload AS jsonb), :evidence, :position)""")
					.param("id", UUID.randomUUID()).param("analysisId", analysisId).param("kind", draft.kind().name())
					.param("field", draft.field()).param("payload", mapper.writeValueAsString(draft.payload()))
					.param("evidence", draft.evidence()).param("position", position++).update();
		}
	}

	/**
	 * Candados de la transacción en curso para reservar el cupo de IA: uno global y uno de la persona, siempre en el
	 * mismo orden (sin riesgo de bloqueo mutuo). Se sueltan solos al terminar la transacción.
	 */
	public void lockAiQuota(UUID userId) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('talenti:ai:cv-read:global', 0))").query().singleRow();
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))").param("key", "talenti:ai:cv-read:user:" + userId)
				.query().singleRow();
	}

	/** La lectura falló (plataforma o tiempo). Solo si seguía en curso. */
	public boolean fail(UUID id, UUID readId, String reason) {
		return jdbc.sql("""
				UPDATE cv_analysis SET status = 'FAILED', failure_reason = :reason
				WHERE id = :id AND status IN ('ANALYZING', 'PENDING') AND current_read_id = :readId""")
				.param("id", id).param("readId", readId).param("reason", reason).update() == 1;
	}

	/**
	 * Cierra el análisis (CONFIRMED, DISCARDED o EXPIRED): borra el texto reducido y las sugerencias (009-10). La fila,
	 * ya sin contenido, se conserva para contar el límite de 24 horas.
	 */
	public boolean close(UUID id, UUID userId, String status, Instant now) {
		int updated = jdbc.sql("""
				UPDATE cv_analysis SET status = :status, reduced_text = NULL, finished_at = :now
				WHERE id = :id AND user_id = :userId AND status IN ('PENDING', 'ANALYZING', 'READY', 'FAILED')""")
				.param("id", id).param("userId", userId).param("status", status).param("now", utc(now)).update();
		if (updated == 1) {
			jdbc.sql("DELETE FROM cv_suggestion WHERE analysis_id = :id").param("id", id).update();
		}
		return updated == 1;
	}

	/** Descarta el análisis fallido que haya quedado, para dejar subir otro archivo. */
	public void discardFailed(UUID userId, Instant now) {
		List<UUID> ids = jdbc.sql("""
				UPDATE cv_analysis SET status = 'DISCARDED', reduced_text = NULL, finished_at = :now
				WHERE user_id = :userId AND status = 'FAILED' RETURNING id""")
				.param("userId", userId).param("now", utc(now)).query(UUID.class).list();
		for (UUID id : ids) {
			jdbc.sql("DELETE FROM cv_suggestion WHERE analysis_id = :id").param("id", id).update();
		}
	}

	/** Un análisis vencido. */
	public record Expired(UUID id, UUID userId) {
	}

	/** Expira lo vencido (abierto o fallido) y borra su contenido. Devuelve lo expirado para auditarlo. */
	public List<Expired> expireOverdue(Instant now) {
		List<Expired> expired = jdbc.sql("""
				UPDATE cv_analysis SET status = 'EXPIRED', reduced_text = NULL, finished_at = :now
				WHERE status IN ('PENDING', 'ANALYZING', 'READY', 'FAILED') AND expires_at <= :now
				RETURNING id, user_id""").param("now", utc(now))
				.query((rs, row) -> new Expired(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class)))
				.list();
		for (Expired item : expired) {
			jdbc.sql("DELETE FROM cv_suggestion WHERE analysis_id = :id").param("id", item.id()).update();
		}
		return expired;
	}

	/** Al arrancar: lo que quedó a medias por un reinicio pasa a FAILED (009-08, 009-32). */
	public int failOrphans() {
		return jdbc.sql("UPDATE cv_analysis SET status = 'FAILED', failure_reason = 'RESTART' "
				+ "WHERE status IN ('PENDING', 'ANALYZING')").update();
	}

}
