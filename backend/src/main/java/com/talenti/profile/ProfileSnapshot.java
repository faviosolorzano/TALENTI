package com.talenti.profile;

import java.util.List;
import java.util.Set;

/**
 * Lo único del perfil que sale del módulo hacia otros (006-13): habilidades con nivel, disponibilidad,
 * preferencias y proyectos. A propósito NO trae correo, carrera, instituto ni ciclo: no entran en ninguna fórmula
 * de compatibilidad (RN-01, principio 3).
 *
 * @param hoursPerWeek nulo si no lo declaró
 * @param location     nulo si no la declaró
 */
public record ProfileSnapshot(List<Skill> skills, Integer hoursPerWeek, Set<WorkModality> acceptedModalities,
		String location, List<Project> projects) {

	public ProfileSnapshot {
		skills = List.copyOf(skills);
		acceptedModalities = Set.copyOf(acceptedModalities);
		projects = List.copyOf(projects);
	}

	/** Una habilidad del catálogo con su nivel (1 básico, 2 intermedio, 3 avanzado). */
	public record Skill(String slug, int level) {
	}

	/** Un proyecto; las tecnologías son texto libre y no cuentan como habilidades (006, duda 5). */
	public record Project(String name, String description, List<String> technologies) {

		public Project {
			technologies = List.copyOf(technologies);
		}

	}

	/** Contenido de la persona: no se imprime en registros. */
	@Override
	public String toString() {
		return "ProfileSnapshot[skills=" + skills.size() + ", projects=" + projects.size() + "]";
	}

}
