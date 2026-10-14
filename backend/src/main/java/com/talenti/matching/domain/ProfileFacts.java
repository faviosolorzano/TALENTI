package com.talenti.matching.domain;

import java.util.Map;
import java.util.Set;

/**
 * Lo único del perfil que entra al cálculo (007-10, RN-01): habilidades con nivel, modalidades aceptadas, ubicación
 * y horas por semana. No hay campo para el nombre, el correo, el instituto, la carrera, el ciclo ni las sesiones de
 * práctica. Los proyectos entrarán (S12) como una similitud ya calculada, no como texto.
 *
 * @param skillLevels      nivel (1 a 3) por {@code slug} de habilidad
 * @param hoursPerWeek     nulo si no lo declaró
 * @param location         nula si no la declaró
 */
public record ProfileFacts(Map<String, Integer> skillLevels, Set<Modality> acceptedModalities, String location,
		Integer hoursPerWeek) {

	public ProfileFacts {
		skillLevels = Map.copyOf(skillLevels);
		acceptedModalities = Set.copyOf(acceptedModalities);
	}

	/** Contenido de la persona: no se imprime en registros. */
	@Override
	public String toString() {
		return "ProfileFacts[skills=" + skillLevels.size() + "]";
	}

}
