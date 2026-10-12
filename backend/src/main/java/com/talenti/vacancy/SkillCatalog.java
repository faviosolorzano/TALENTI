package com.talenti.vacancy;

import java.util.List;
import java.util.Optional;

/**
 * Interfaz pública del catálogo de habilidades con sinónimos (RN-03). El catálogo vive en el módulo vacancy
 * (spec 004, duda 8) y lo usará S3 (perfil) sin tocar sus tablas.
 */
public interface SkillCatalog {

	/** Todas las habilidades, ordenadas por nombre. */
	List<SkillInfo> all();

	/** La habilidad con ese {@code slug} exacto, si existe. */
	Optional<SkillInfo> findBySlug(String slug);

	/**
	 * Busca una habilidad por su nombre o por uno de sus sinónimos, sin distinguir mayúsculas ni tildes
	 * ("js" y "JavaScript" dan la misma). Vacío si el texto no está en el catálogo: una sugerencia así no cuenta
	 * para el cálculo hasta asociarla a una habilidad del catálogo (RN-03).
	 */
	Optional<SkillInfo> resolve(String text);

}
