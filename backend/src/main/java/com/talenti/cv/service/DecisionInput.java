package com.talenti.cv.service;

import tools.jackson.databind.JsonNode;

/**
 * Una decisión de la persona sobre una sugerencia (009-15): {@code ACCEPT}, {@code EDIT} o {@code DISCARD}. {@code value}
 * trae lo que cambia sobre la sugerencia (por ejemplo el nivel de una habilidad). Es contenido de la persona.
 */
public record DecisionInput(String suggestionId, String action, JsonNode value) {

	@Override
	public String toString() {
		return "DecisionInput[...]";
	}

}
