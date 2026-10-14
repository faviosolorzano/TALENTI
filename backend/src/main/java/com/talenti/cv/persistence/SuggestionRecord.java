package com.talenti.cv.persistence;

import java.util.UUID;

import com.talenti.cv.SuggestionKind;

import tools.jackson.databind.JsonNode;

/** Una fila de {@code cv_suggestion}. Es contenido del CV: su {@code toString} no lo muestra. */
public record SuggestionRecord(UUID id, SuggestionKind kind, String field, JsonNode payload, String evidence,
		int position) {

	@Override
	public String toString() {
		return "SuggestionRecord[id=" + id + ", kind=" + kind + "]";
	}

}
