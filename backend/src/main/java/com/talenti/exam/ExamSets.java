package com.talenti.exam;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Lectura pública para la futura cara de empresa; ninguna operación modifica una versión usada. */
public interface ExamSets {
	record Version(UUID id,String label,boolean locked,String verificationReport) { }
	record Set(UUID id,UUID vacancyId,int revision,String status,String source,List<Version> versions,Instant readyAt) { }
	Optional<Set> forVacancy(UUID vacancyId);
}
