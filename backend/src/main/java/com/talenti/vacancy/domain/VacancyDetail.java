package com.talenti.vacancy.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Detalle de una vacante (abierta o cerrada) con sus requisitos obligatorios y deseables por separado. */
public record VacancyDetail(UUID id, String title, String summary, String description, Modality modality,
		String location, int hoursPerWeek, Instant publishedAt, VacancyStatus status, boolean featured,
		CompanyDetail company, List<SkillTag> skills, Requirements requirements) {
}
