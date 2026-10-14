package com.talenti.matching.domain;

import java.util.List;

/**
 * Lo que entra al cálculo de una vacante: su modalidad, ciudad, horas y requisitos.
 *
 * @param location ciudad; nula si es remota
 */
public record VacancyFacts(Modality modality, String location, int hoursPerWeek, List<Requirement> requirements) {

	public VacancyFacts {
		requirements = List.copyOf(requirements);
	}

	public record Requirement(String slug, String name, RequirementKind kind, int minLevel) {
	}

}
