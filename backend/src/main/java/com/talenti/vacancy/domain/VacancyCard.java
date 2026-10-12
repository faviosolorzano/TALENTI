package com.talenti.vacancy.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Vacante en el listado, la portada y la franja de destacadas. Sin porcentaje de compatibilidad (es de S4). */
public record VacancyCard(UUID id, String title, String summary, Modality modality, String location,
		int hoursPerWeek, Instant publishedAt, boolean featured, CompanyRef company, List<SkillTag> skills) {

	public VacancyCard {
		skills = List.copyOf(skills);
	}

	public VacancyCard withSkills(List<SkillTag> skills) {
		return new VacancyCard(id, title, summary, modality, location, hoursPerWeek, publishedAt, featured, company,
				skills);
	}

}
