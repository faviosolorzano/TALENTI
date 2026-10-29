package com.talenti.exam.snapshot;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Único acceso a {@code exam_snapshot}: solo la ficha de cada foto, nunca la imagen. */
@Repository
public class SnapshotStore {
	private final JdbcClient jdbc;
	public SnapshotStore(JdbcClient jdbc) { this.jdbc=jdbc; }
	public record Row(UUID id,UUID attemptId,String reason,UUID eventClientId,String storageKey,int byteSize,int width,int height,
			String sha256,Instant clientTakenAt,Instant createdAt,Instant purgeAfter) { }
	private static OffsetDateTime utc(Instant i) { return i==null?null:OffsetDateTime.ofInstant(i,ZoneOffset.UTC); }
	private static Instant instant(ResultSet r,String c) throws SQLException { var v=r.getObject(c,OffsetDateTime.class); return v==null?null:v.toInstant(); }
	private static Row row(ResultSet r,int n) throws SQLException {
		return new Row(r.getObject("id",UUID.class),r.getObject("attempt_id",UUID.class),r.getString("reason"),r.getObject("event_client_id",UUID.class),
				r.getString("storage_key"),r.getInt("byte_size"),r.getInt("width"),r.getInt("height"),r.getString("sha256"),
				instant(r,"client_taken_at"),instant(r,"created_at"),instant(r,"purge_after"));
	}
	public void insert(Row s) {
		jdbc.sql("""
				INSERT INTO exam_snapshot(id,attempt_id,reason,event_client_id,storage_key,byte_size,width,height,sha256,client_taken_at,created_at,purge_after)
				VALUES (:id,:attempt,:reason,:event,:key,:size,:width,:height,:sha,:clientAt,:createdAt,:purge)""")
				.param("id",s.id()).param("attempt",s.attemptId()).param("reason",s.reason()).param("event",s.eventClientId())
				.param("key",s.storageKey()).param("size",s.byteSize()).param("width",s.width()).param("height",s.height())
				.param("sha",s.sha256()).param("clientAt",utc(s.clientTakenAt())).param("createdAt",utc(s.createdAt())).param("purge",utc(s.purgeAfter())).update();
	}
	public int count(UUID attempt) {
		return jdbc.sql("SELECT count(*) FROM exam_snapshot WHERE attempt_id=:a").param("a",attempt).query(Integer.class).single();
	}
	public List<Row> forAttempt(UUID attempt) {
		return jdbc.sql("SELECT * FROM exam_snapshot WHERE attempt_id=:a ORDER BY created_at,id").param("a",attempt).query(SnapshotStore::row).list();
	}
	public Optional<Row> find(UUID attempt,UUID id) {
		return jdbc.sql("SELECT * FROM exam_snapshot WHERE attempt_id=:a AND id=:id").param("a",attempt).param("id",id).query(SnapshotStore::row).optional();
	}
	/** 014-56: el plazo corre desde el fin del intento. */
	public void schedulePurge(UUID attempt,Instant purgeAfter) {
		jdbc.sql("UPDATE exam_snapshot SET purge_after=:p WHERE attempt_id=:a").param("a",attempt).param("p",utc(purgeAfter)).update();
	}
	public List<Row> expired(Instant now,int limit) {
		return jdbc.sql("SELECT * FROM exam_snapshot WHERE purge_after<=:now ORDER BY purge_after LIMIT :limit")
				.param("now",utc(now)).param("limit",limit).query(SnapshotStore::row).list();
	}
	public boolean delete(UUID id) { return jdbc.sql("DELETE FROM exam_snapshot WHERE id=:id").param("id",id).update()==1; }
	/** Borra las fichas de un intento y devuelve sus claves para borrar los archivos después de confirmar. */
	public List<String> deleteForAttempt(UUID attempt) {
		return jdbc.sql("DELETE FROM exam_snapshot WHERE attempt_id=:a RETURNING storage_key").param("a",attempt).query(String.class).list();
	}
	public Set<String> existingKeys(Collection<String> keys) {
		if(keys.isEmpty()) return Set.of();
		return new HashSet<>(jdbc.sql("SELECT storage_key FROM exam_snapshot WHERE storage_key IN (:keys)").param("keys",keys).query(String.class).list());
	}
}
