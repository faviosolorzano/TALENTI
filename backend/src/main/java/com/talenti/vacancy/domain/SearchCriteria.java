package com.talenti.vacancy.domain;

import java.util.List;

/**
 * Lo que se pide al listado, ya validado. Dentro de cada lista basta una coincidencia (O); entre listas deben
 * cumplirse todas (Y). Una lista vacía no filtra.
 *
 * @param terms      palabras útiles de {@code q} (ya sin las de relleno); basta que una aparezca en título, resumen,
 *                   empresa o habilidades (004-27). Sin palabras no se filtra por texto
 * @param modalities modalidades
 * @param locations  ciudades
 * @param skills     {@code slug} de habilidades (obligatorias o deseables)
 * @param sort       orden pedido, o {@code null} si no se pidió; se guarda ya resuelto ({@link SearchSort#effective})
 */
public record SearchCriteria(List<String> terms, List<Modality> modalities, List<String> locations,
		List<String> skills, SearchSort sort) {

	public SearchCriteria {
		terms = List.copyOf(terms);
		modalities = List.copyOf(modalities);
		locations = List.copyOf(locations);
		skills = List.copyOf(skills);
		sort = SearchSort.effective(sort, !terms.isEmpty());
	}

}
