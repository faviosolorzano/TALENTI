package com.talenti.vacancy.domain;

import java.util.Optional;

/** Orden del listado. Ninguno depende de {@code featured}: destacar no cambia la posición (principio 3). */
public enum SearchSort {

	/** Publicación descendente; el {@code id} desempata para que las páginas no repitan ni omitan. */
	RECENT("recent"),
	/** Nombre de la empresa ascendente (sin distinguir tildes), luego lo más reciente. */
	COMPANY("company");

	private final String value;

	SearchSort(String value) {
		this.value = value;
	}

	public String value() {
		return value;
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
