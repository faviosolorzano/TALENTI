package com.talenti.vacancy;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Interfaz pública de consulta de vacantes para otros módulos (spec 007, sección 5): compatibilidad la usa para
 * calcular y postulación (008) para mostrar tarjetas. Solo lectura.
 */
public interface VacancyLookup {

	/** Todas las vacantes abiertas con sus requisitos. */
	List<VacancyInfo> listOpenForMatching();

	/** La vacante con ese id si está abierta; vacío si no existe o está cerrada (no se distinguen). */
	Optional<VacancyInfo> findOpenForMatching(UUID id);

	/** Las vacantes pedidas, abiertas o cerradas (con su estado); se omiten los ids desconocidos. */
	List<VacancyInfo> findCards(Collection<UUID> ids);

}
