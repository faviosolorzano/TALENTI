package com.talenti.cv.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * El análisis tal como lo ve la persona (spec 009, sección 6). Las sugerencias solo vienen en {@code READY}. Contiene
 * contenido del CV: su {@code toString} no lo muestra.
 *
 * @param source            {@code RULES} o {@code AI}: decide si la pantalla lleva el rótulo de IA (T1)
 * @param level             {@code BASIC} o {@code ENHANCED}
 * @param alreadyInProfile  sugerencias que no se muestran porque el perfil ya las tiene (009-14)
 * @param suggestions       agrupables por {@code kind} en la interfaz
 */
public record AnalysisView(UUID id, String status, String source, String level, boolean reused, boolean degraded,
		String degradedReason, Instant createdAt, Instant expiresAt, String failureReason, boolean inputTruncated,
		int alreadyInProfile, List<SuggestionView> suggestions) {

	public AnalysisView {
		suggestions = List.copyOf(suggestions);
	}

	/**
	 * @param value        forma por tipo: habilidad {@code {slug|null, name, level|null}}, estudio {@code {value}},
	 *                     proyecto {@code {name, description, technologies[]}}
	 * @param currentValue lo que el perfil tiene hoy en ese dato (texto, número o nulo)
	 */
	public record SuggestionView(UUID id, String kind, String field, JsonNode value, String evidence,
			Object currentValue) {

		@Override
		public String toString() {
			return "SuggestionView[id=" + id + ", kind=" + kind + "]";
		}

	}

	@Override
	public String toString() {
		return "AnalysisView[id=" + id + ", status=" + status + ", suggestions=" + suggestions.size() + "]";
	}

}
