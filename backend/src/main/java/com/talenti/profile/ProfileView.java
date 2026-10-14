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
public record ProfileView(About about, Academic academic, List<SkillEntry> skills, Availability availability,
		List<ProjectEntry> projects, ProfileCompleteness completeness, Instant updatedAt) {

	public ProfileView {
		skills = List.copyOf(skills);
		projects = List.copyOf(projects);
	}

	/**
	 * "Sobre ti" (enmienda 1): cómo quiere que le llamen y su presentación; cada campo es nulo si no se declaró. El
	 * nombre solo sirve para el saludo y la pantalla del perfil (006-25): no sale hacia otros módulos.
	 */
	public record About(String firstNames, String lastNames, String contactEmail, String phone, String about) {

		@Override
		public String toString() {
			return "About[...]";
		}

	}

	/** Datos académicos; cada campo es nulo si no se declaró. */
	public record Academic(String career, String institute, Integer cycle) {

		@Override
		public String toString() {
			return "Academic[...]";
		}

	}

	public record SkillEntry(String slug, String name, int level, SkillEvidence evidence) {

		@Override
		public String toString() {
			return "SkillEntry[...]";
		}

	}

	public record Availability(Integer hoursPerWeek, List<WorkModality> acceptedModalities, String location) {

		public Availability {
			acceptedModalities = List.copyOf(acceptedModalities);
		}

		@Override
		public String toString() {
			return "Availability[...]";
		}

	}

	public record ProjectEntry(UUID id, String name, String description, List<String> technologies) {

		public ProjectEntry {
			technologies = List.copyOf(technologies);
		}

		@Override
		public String toString() {
			return "ProjectEntry[id=" + id + "]";
		}

	}

	/** Contenido de la persona: no se imprime en registros. */
	@Override
	public String toString() {
		return "ProfileView[skills=" + skills.size() + ", projects=" + projects.size() + "]";
	}

}
