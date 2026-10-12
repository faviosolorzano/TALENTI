package com.talenti.vacancy.domain;

import java.util.List;

/**
 * Opciones de los filtros con su conteo sobre TODAS las vacantes abiertas (no según lo ya elegido), y el total de
 * vacantes abiertas para el encabezado de la portada.
 */
public record FilterOptions(long openCount, List<ModalityOption> modalities, List<LocationOption> locations,
		List<SkillOption> skills) {

	public record ModalityOption(Modality value, long count) {
	}

	public record LocationOption(String value, long count) {
	}

	public record SkillOption(String slug, String name, long count) {
	}

}
