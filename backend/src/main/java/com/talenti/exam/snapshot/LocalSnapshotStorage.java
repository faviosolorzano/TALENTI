package com.talenti.exam.snapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/**
 * Adaptador de disco (014-54): una carpeta por intento dentro del volumen {@code exam-snapshots}. Escribe en un archivo
 * temporal y lo mueve de forma atómica; solo el usuario del backend puede leer (donde el sistema lo admite).
 */
public class LocalSnapshotStorage implements ExamSnapshotStorage {
	private final Path root;
	public LocalSnapshotStorage(Path root) { this.root=root.toAbsolutePath().normalize(); }
	private Path path(String key) {
		Path p=root.resolve(ExamSnapshotStorage.requireKey(key)).normalize();
		if(!p.startsWith(root)) throw new IllegalArgumentException("Clave de foto inválida");
		return p;
	}
	@Override public void put(String key,byte[] jpeg) {
		Path target=path(key);
		try {
			Files.createDirectories(target.getParent());
			restrict(target.getParent(),"rwx------");
			Path temp=Files.createTempFile(target.getParent(),".upload-",".tmp");
			try {
				restrict(temp,"rw-------");
				Files.write(temp,jpeg);
				Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE);
			}
			finally { Files.deleteIfExists(temp); }
		}
		catch(IOException e) { throw new UncheckedIOException("No se pudo guardar la foto del examen",e); }
	}
	@Override public Optional<byte[]> read(String key) {
		Path p=path(key);
		try { return Files.isRegularFile(p)?Optional.of(Files.readAllBytes(p)):Optional.empty(); }
		catch(NoSuchFileException e) { return Optional.empty(); }
		catch(IOException e) { throw new UncheckedIOException("No se pudo leer la foto del examen",e); }
	}
	@Override public void delete(String key) {
		Path p=path(key);
		try {
			Files.deleteIfExists(p);
			try { Files.deleteIfExists(p.getParent()); } catch(DirectoryNotEmptyException ignored) { /* quedan otras fotos */ }
		}
		catch(IOException e) { throw new UncheckedIOException("No se pudo borrar la foto del examen",e); }
	}
	@Override public List<Stored> list() {
		if(!Files.isDirectory(root)) return List.of();
		List<Stored> out=new ArrayList<>();
		try(var dirs=Files.newDirectoryStream(root,Files::isDirectory)) {
			for(Path dir:dirs) try(var files=Files.newDirectoryStream(dir,"*.jpg")) {
				for(Path f:files) {
					String key=dir.getFileName()+"/"+f.getFileName();
					if(ExamSnapshotStorage.KEY.matcher(key).matches()) out.add(new Stored(key,Files.getLastModifiedTime(f).toInstant()));
				}
			}
		}
		catch(IOException e) { throw new UncheckedIOException("No se pudo recorrer las fotos del examen",e); }
		return out;
	}
	private static void restrict(Path path,String permissions) {
		try { Files.setPosixFilePermissions(path,PosixFilePermissions.fromString(permissions)); }
		catch(UnsupportedOperationException|IOException ignored) { /* Windows en desarrollo: sin permisos POSIX */ }
	}
}
