package com.talenti.vacancy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Una vacante con los datos de su tarjeta y sus requisitos, para otros módulos (compatibilidad, postulación). Es
 * parte de la interfaz pública de vacancy: otros módulos la reciben de {@link VacancyLookup}, nunca leen las
 * tablas {@code vacancy}, {@code company} ni {@code vacancy_requirement}.
 *
 * @param location     ciudad; nula si la vacante es remota
 * @param requirements obligatorios primero, luego por nivel mínimo descendente y nombre
 */
public record VacancyInfo(UUID id, String title, String summary, Modality modality, String location,
		int hoursPerWeek, Instant publishedAt, boolean featured, Status status, Company company,
		List<Requirement> requirements) {

	public VacancyInfo {
		requirements = List.copyOf(requirements);
	}

	/** Modalidad de la vacante; mismos valores que la API. */
	public enum Modality {

		REMOTE, HYBRID, ONSITE

	}

	public enum Status {

		OPEN, CLOSED

	}

	public enum RequirementKind {

		REQUIRED, DESIRABLE

	}

	public record Company(UUID id, String name, String city) {
	}

	/** Un requisito: la habilidad del catálogo, su tipo y el nivel mínimo (1 básico, 2 intermedio, 3 avanzado). */
	public record Requirement(String slug, String name, RequirementKind kind, int minLevel) {
	}

}
