package com.talenti.profile;

import java.util.List;
import java.util.UUID;

/**
 * Interfaz pública del perfil para las habilidades vistas en GitHub (spec 013, 013-12 y 013-15). Solo la usa el
 * módulo {@code github} (lo vigila {@code ProfileArchitectureTests}). Se une a la transacción abierta, así quien la
 * llama guarda lo suyo y lo del perfil juntos o nada.
 */
public interface SkillEvidenceWriter {

	/** Las habilidades que la persona ya tiene, con su nivel y su evidencia. No crea filas. */
	List<CurrentSkill> currentSkills(UUID userId);

	/**
	 * Guarda como {@code OBSERVED} lo que la persona confirmó (013-12). Si la habilidad no estaba, nace con el nivel
	 * que eligió (obligatorio, 1 a 3); si ya estaba, conserva su nivel salvo que llegue otro. Una habilidad
	 * {@code EVALUATED} conserva esa evidencia (es más fuerte). Con la lista vacía no cambia nada.
	 *
	 * @return el perfil resultante, con el avance recalculado
	 * @throws com.talenti.shared.web.ValidationFailedException 422 ({@code skills[i].slug} o {@code skills[i].level})
	 */
	ProfileView confirmObserved(UUID userId, List<ObservedSkill> skills);

	/** Al desvincular GitHub (013-15): las habilidades {@code OBSERVED} quedan como {@code DECLARED}. */
	void downgradeObserved(UUID userId);

	/** Una habilidad del perfil por su {@code slug} del catálogo. */
	record CurrentSkill(String slug, int level, SkillEvidence evidence) {
	}

	/** Lo confirmado: el {@code slug} del catálogo y el nivel elegido (nulo = conservar el que ya tiene). */
	record ObservedSkill(String slug, Integer level) {
	}

}
