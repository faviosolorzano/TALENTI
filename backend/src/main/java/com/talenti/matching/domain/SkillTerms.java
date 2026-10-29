package com.talenti.matching.domain;

import java.util.List;

/**
 * Cómo puede aparecer una habilidad en un texto: su nombre de catálogo y sus sinónimos (RN-03). La validación de la
 * salida de la IA los busca para saber qué habilidades menciona cada sección (015-07).
 */
public record SkillTerms(String slug, List<String> terms) {

	public SkillTerms {
		terms = List.copyOf(terms);
	}

}
