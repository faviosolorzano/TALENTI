package com.talenti.profile.domain;

/**
 * Lo que la regla de avance necesita saber del perfil, sin el contenido: solo si hay o no cada dato y cuántos hay (el nombre no cuenta, 006-24).
 * Así la regla es una función pura y nada de la persona termina en un registro.
 */
public record ProfileFacts(boolean hasCareer, boolean hasInstitute, boolean hasCycle, int skillCount,
		boolean hasHoursPerWeek, int modalityCount, int projectCount, boolean hasLocation, boolean hasAbout) {

	public static ProfileFacts empty() {
		return new ProfileFacts(false, false, false, 0, false, 0, 0, false, false);
	}

}
