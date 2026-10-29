package com.talenti.matching.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * La tabla {@code compatibility_explanation} (V14, propiedad de matching). Solo guarda explicaciones de IA ya validadas
 * (015-12), una por persona y vacante; el contenido va como JSON y nunca se imprime. Es lo único de matching que toca
 * JDBC.
 */
@Repository
public class ExplanationStore {

	private final JdbcClient jdbc;

	ExplanationStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Lo guardado; {@code content} es el JSON de las secciones y la práctica. */
	public record Stored(String inputHash, String formulaVersion, String promptVersion, String templateVersion,
			String model, String adapter, String content, Instant generatedAt) {

		@Override
		public String toString() {
			return "Stored[adapter=" + adapter + "]";
		}

	}

	/**
	 * 015-17: bloqueo por persona + vacante hasta el final de la transacción en curso. Dos peticiones simultáneas se
	 * ordenan: la segunda espera y luego lee lo que dejó la primera. Debe llamarse dentro de una transacción.
	 */
	public void lock(UUID userId, UUID vacancyId) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
				.param("key", "compatibility_explanation:" + userId + ":" + vacancyId).query((rs, n) -> 1).list();
	}

	public Optional<Stored> find(UUID userId, UUID vacancyId) {
		return jdbc.sql("""
				SELECT input_hash, formula_version, prompt_version, template_version, model, adapter, content::text AS content,
				       generated_at
				FROM compatibility_explanation WHERE user_id = :userId AND vacancy_id = :vacancyId""")
				.param("userId", userId).param("vacancyId", vacancyId)
				.query((rs, n) -> new Stored(rs.getString("input_hash"), rs.getString("formula_version"),
						rs.getString("prompt_version"), rs.getString("template_version"), rs.getString("model"),
						rs.getString("adapter"), rs.getString("content"),
						rs.getObject("generated_at", OffsetDateTime.class).toInstant()))
				.optional();
	}

	/** Guarda o reemplaza la explicación de esa persona y vacante (015-12: se sobrescribe al regenerar). */
	public void save(UUID userId, UUID vacancyId, Stored stored) {
		jdbc.sql("""
				INSERT INTO compatibility_explanation (id, user_id, vacancy_id, input_hash, formula_version, prompt_version,
				                                       template_version, model, adapter, content, generated_at)
				VALUES (:id, :userId, :vacancyId, :hash, :formula, :prompt, :template, :model, :adapter,
				        CAST(:content AS jsonb), :generatedAt)
				ON CONFLICT (user_id, vacancy_id) DO UPDATE SET
				    input_hash = EXCLUDED.input_hash, formula_version = EXCLUDED.formula_version,
				    prompt_version = EXCLUDED.prompt_version, template_version = EXCLUDED.template_version,
				    model = EXCLUDED.model, adapter = EXCLUDED.adapter, content = EXCLUDED.content,
				    generated_at = EXCLUDED.generated_at""")
				.param("id", UUID.randomUUID()).param("userId", userId).param("vacancyId", vacancyId)
				.param("hash", stored.inputHash()).param("formula", stored.formulaVersion())
				.param("prompt", stored.promptVersion()).param("template", stored.templateVersion())
				.param("model", stored.model()).param("adapter", stored.adapter()).param("content", stored.content())
				.param("generatedAt", OffsetDateTime.ofInstant(stored.generatedAt(), ZoneOffset.UTC))
				.update();
	}

}
