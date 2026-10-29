package com.talenti.exam.snapshot;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Puerto de almacenamiento de las fotos del examen (014-54). Adaptadores: disco en el volumen Docker
 * {@code exam-snapshots} ({@link LocalSnapshotStorage}) y en memoria para pruebas ({@link MemorySnapshotStorage});
 * uno de objetos (S3) al desplegar. Las claves son {@code {attemptId}/{snapshotId}.jpg}: sin nombres de personas.
 * Nada de este paquete llega a un proveedor de IA (014-55).
 */
public interface ExamSnapshotStorage {
	Pattern KEY=Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}[.]jpg");
	record Stored(String key,Instant modifiedAt) { }
	void put(String key,byte[] jpeg);
	Optional<byte[]> read(String key);
	/** Borra si existe; no falla si ya no está. */
	void delete(String key);
	/** Todas las fotos guardadas, para el barrido de huérfanas (cuenta eliminada, 014-56). */
	List<Stored> list();
	static String key(UUID attempt,UUID snapshot) { return attempt+"/"+snapshot+".jpg"; }
	static String requireKey(String key) {
		if(key==null||!KEY.matcher(key).matches()) throw new IllegalArgumentException("Clave de foto inválida");
		return key;
	}
}
