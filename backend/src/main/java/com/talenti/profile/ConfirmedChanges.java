package com.talenti.profile;

import java.util.List;

/**
 * Lo que la persona confirmó de las sugerencias del lector de CV (spec 009). Se valida con las mismas reglas
 * que las pantallas de 006.
 *
 * @param academic nulo si no cambia ningún dato académico; dentro, un campo nulo significa "no cambia"
 * @param skills   habilidades por {@code slug} del catálogo: agrega las nuevas y actualiza el nivel de las que ya
 *                 existen (siempre con evidencia {@code DECLARED})
 * @param projects proyectos que se agregan (cuentan para el límite de proyectos)
 */
public record ConfirmedChanges(Academic academic, List<Skill> skills, List<Project> projects) {

	public ConfirmedChanges {
		skills = skills == null ? List.of() : List.copyOf(skills);
		projects = projects == null ? List.of() : List.copyOf(projects);
	}

	public record Academic(String career, String institute, Integer cycle) {
	}

	public record Skill(String slug, Integer level) {
	}

	public record Project(String name, String description, List<String> technologies) {
	}

	/** Contenido de la persona: no se imprime en registros. */
	@Override
	public String toString() {
		return "ConfirmedChanges[skills=" + skills.size() + ", projects=" + projects.size() + "]";
	}

}
