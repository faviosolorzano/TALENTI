package com.talenti.vacancy.domain;

import java.util.List;

/** Lo que devuelve la consulta del listado: las tarjetas de la página pedida y el total que cumple el filtro. */
public record SearchResult(List<VacancyCard> items, long total) {

	public SearchResult {
		items = List.copyOf(items);
	}

}
