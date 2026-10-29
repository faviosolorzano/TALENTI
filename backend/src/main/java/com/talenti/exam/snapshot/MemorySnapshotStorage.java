package com.talenti.exam.snapshot;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Adaptador simulado (pruebas): guarda en memoria con la hora del reloj del sistema de Talenti. */
public class MemorySnapshotStorage implements ExamSnapshotStorage {
	private record Entry(byte[] bytes,java.time.Instant at) { }
	private final Map<String,Entry> files=new ConcurrentHashMap<>();
	private final Clock clock;
	public MemorySnapshotStorage(Clock clock) { this.clock=clock; }
	@Override public void put(String key,byte[] jpeg) { files.put(ExamSnapshotStorage.requireKey(key),new Entry(jpeg.clone(),clock.instant())); }
	@Override public Optional<byte[]> read(String key) { return Optional.ofNullable(files.get(ExamSnapshotStorage.requireKey(key))).map(e->e.bytes().clone()); }
	@Override public void delete(String key) { files.remove(ExamSnapshotStorage.requireKey(key)); }
	@Override public List<Stored> list() { return files.entrySet().stream().map(e->new Stored(e.getKey(),e.getValue().at())).toList(); }
	public boolean contains(String key) { return files.containsKey(key); }
	public int size() { return files.size(); }
}
