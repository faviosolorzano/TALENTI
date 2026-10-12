package com.talenti.vacancy.domain;

import java.util.Optional;

/** Orden del listado. Ninguno depende de {@code featured}: destacar no cambia la posición (principio 3). */
public enum SearchSort {

	/** Publicación descendente; el {@code id} desempata para que las páginas no repitan ni omitan. */
	RECENT("recent"),
	/** Nombre de la empresa ascendente (sin distinguir tildes), luego lo más reciente. */
	COMPANY("company"),
	/**
	 * Parecido con el texto buscado (004-28), de mayor a menor; luego publicación descendente y {@code id}. Solo
	 * tiene sentido con palabras útiles en {@code q}: sin ellas se comporta como {@link #RECENT} (004-29).
	 */
	RELEVANCE("relevance");

	private final String value;

	SearchSort(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	/**
	 * El orden que de verdad se aplica (004-29): si no se pidió ninguno, {@link #RELEVANCE} cuando hay palabras
	 * útiles y {@link #RECENT} cuando no; y {@link #RELEVANCE} sin palabras útiles se comporta como {@link #RECENT}.
	 */
	public static SearchSort effective(SearchSort requested, boolean hasTerms) {
		if (requested == null) {
			return hasTerms ? RELEVANCE : RECENT;
		}
		return requested == RELEVANCE && !hasTerms ? RECENT : requested;
	}

	public static Optional<SearchSort> parse(String value) {
		for (SearchSort sort : values()) {
			if (sort.value.equals(value)) {
				return Optional.of(sort);
			}
		}
		return Optional.empty();
	}

}
