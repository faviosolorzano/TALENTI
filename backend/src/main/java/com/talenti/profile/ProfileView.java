package com.talenti.profile;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * El perfil completo de su dueño: es el cuerpo de todas las respuestas de {@code /profile} (spec 006, sección 6).
 * Solo lo ve la propia persona: no incluye el correo y no sale hacia otros módulos (para ellos está
 * {@link ProfileSnapshot}).
 *
 * @param updatedAt nulo mientras el perfil no tenga ninguna escritura
 */
public record ProfileView(Academic academic, List<SkillEntry> skills, Availability availability,
		List<ProjectEntry> projects, ProfileCompleteness completeness, Instant updatedAt) {

	public ProfileView {
		skills = List.copyOf(skills);
		projects = List.copyOf(projects);
	}

	/** Datos académicos; cada campo es nulo si no se declaró. */
	public record Academic(String career, String institute, Integer cycle) {
	}

	public record SkillEntry(String slug, String name, int level, SkillEvidence evidence) {
	}

	public record Availability(Integer hoursPerWeek, List<WorkModality> acceptedModalities, String location) {

		public Availability {
			acceptedModalities = List.copyOf(acceptedModalities);
		}

	}

	public record ProjectEntry(UUID id, String name, String description, List<String> technologies) {

		public ProjectEntry {
			technologies = List.copyOf(technologies);
		}

	}

	/** Contenido de la persona: no se imprime en registros. */
	@Override
	public String toString() {
		return "ProfileView[skills=" + skills.size() + ", projects=" + projects.size() + "]";
	}

}
