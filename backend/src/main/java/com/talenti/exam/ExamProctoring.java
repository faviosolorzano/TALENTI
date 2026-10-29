package com.talenti.exam;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Interfaz pública para la futura cara de la empresa de esa vacante (014-57): abre una foto del examen de una
 * postulación. No hay rutas de empresa todavía. Las fotos nunca se entregan a la IA ni a otro proveedor (014-55).
 */
public interface ExamProctoring {
	record Image(UUID snapshotId,Instant takenAt,String reason,byte[] jpeg) {
		@Override public String toString() { return "ExamSnapshotImage[id="+snapshotId+", reason="+reason+"]"; }
	}
	/** La foto si pertenece al intento de esa postulación; vacío si no existe, es de otra o ya se borró. */
	Optional<Image> openSnapshot(UUID applicationId,UUID snapshotId);
}
