package com.talenti.cv.domain;

import java.util.Map;

import com.talenti.cv.SuggestionKind;

/**
 * Una sugerencia ya validada, lista para guardarse (009-13). El {@code payload} tiene la forma fija de su tipo:
 * habilidad {@code {slug|null, name, level|null}}, estudio {@code {value}} y proyecto
 * {@code {name, description, technologies[]}}.
 *
 * @param field {@code CAREER}, {@code INSTITUTE} o {@code CYCLE} para los estudios; nulo en los demás
 */
public record SuggestionDraft(SuggestionKind kind, String field, Map<String, Object> payload, String evidence) {

	public SuggestionDraft {
		payload = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(payload));
	}

	/** Contenido del CV: no se imprime en registros. */
	@Override
	public String toString() {
		return "SuggestionDraft[kind=" + kind + "]";
	}

}
