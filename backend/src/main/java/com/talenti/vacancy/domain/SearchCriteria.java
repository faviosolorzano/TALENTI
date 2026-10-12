package com.talenti.vacancy.domain;

import java.util.List;

/**
 * Lo que se pide al listado, ya validado. Dentro de cada lista basta una coincidencia (O); entre listas deben
 * cumplirse todas (Y). Una lista vacía no filtra.
 *
 * @param terms      términos de {@code q}; cada uno debe coincidir en título, resumen, empresa o habilidades
 * @param modalities modalidades
 * @param locations  ciudades
 * @param skills     {@code slug} de habilidades (obligatorias o deseables)
 */
public record SearchCriteria(List<String> terms, List<Modality> modalities, List<String> locations,
		List<String> skills, SearchSort sort) {

	public SearchCriteria {
		terms = List.copyOf(terms);
		modalities = List.copyOf(modalities);
		locations = List.copyOf(locations);
		skills = List.copyOf(skills);
	}

}
