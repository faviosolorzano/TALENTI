package com.talenti.vacancy.domain;

import java.util.Optional;

/** Modalidad de la vacante. Los valores son los mismos de la columna {@code vacancy.modality} y de la API. */
public enum Modality {

	REMOTE, HYBRID, ONSITE;

	public static Optional<Modality> parse(String value) {
		for (Modality modality : values()) {
			if (modality.name().equals(value)) {
				return Optional.of(modality);
			}
		}
		return Optional.empty();
	}

}
