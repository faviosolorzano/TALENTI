package com.talenti.exam.domain;

import java.util.*;
import com.talenti.exam.domain.AttemptRules.EventKind;

/**
 * Reglas del servidor para la supervisión (spec 014, enmienda 1). El navegador decide los episodios con los umbrales;
 * aquí solo se valida lo que llega (lista cerrada de campos, 014-51) y se garantiza que ningún hecho de cámara,
 * detección, copiado o impresión cambia el intento (014-52). Nada se rotula como fraude ni sospecha (principio 6).
 */
public final class ProctoringRules {
	private ProctoringRules() { }
	/** Tipos con episodios inicio/fin (014-50). */
	public static final Set<EventKind> EPISODIC=Collections.unmodifiableSet(EnumSet.of(EventKind.GAZE_AWAY,
			EventKind.FACE_MISSING,EventKind.MULTIPLE_FACES,EventKind.PHONE_VISIBLE));
	public static final long MAX_MS=3_600_000L;
	public static final int MIN_FACES=2, MAX_FACES=10;
	public static final String COMPANY_LABEL="Hechos registrados durante el examen; no indican fraude. La decisión es de la empresa.";
	public enum Phase { START, END }
	public enum Detection { AVAILABLE, UNAVAILABLE }
	public enum SnapshotReason { START, PERIODIC, EVENT }
	public record Calibration(double baseYawDeg,double basePitchDeg) { }
	/** Dato del evento rechazado: {@code field} es la clave (o "data"), nunca el valor recibido. */
	public static final class InvalidData extends RuntimeException {
		private final String field;
		public InvalidData(String field) { super(null,null,false,false); this.field=field; }
		public String field() { return field; }
	}
	/** 014-52: solo salir de la pantalla aplica la regla aviso-cierre; lo demás solo se guarda. */
	public static boolean changesAttempt(EventKind kind) { return kind==EventKind.SCREEN_LEFT; }
	/** Un aviso que ya informa cámara, fotos y detección (v2 en adelante). Sin él no se toman ni aceptan fotos. */
	public static boolean noticeIncludesCamera(String noticeVersion) { return noticeVersion!=null&&!noticeVersion.equals("v1"); }

	/**
	 * 014-51: {@code data} solo admite {@code episodeId} (UUID), {@code phase} (START/END), {@code sinceMs} (solo en
	 * START) y {@code durationMs} (solo en END), enteros de 0 a 3 600 000, y {@code faces} (2 a 10, solo en
	 * MULTIPLE_FACES). Solo los tipos con episodios llevan datos; {@code episodeId} y {@code phase} van juntos.
	 */
	public static Map<String,Object> eventData(EventKind kind,Map<String,?> data) {
		if(data==null||data.isEmpty()) return Map.of();
		if(!EPISODIC.contains(kind)) throw new InvalidData("data");
		var out=new LinkedHashMap<String,Object>();
		for(var entry:data.entrySet()) {
			String key=entry.getKey(); Object value=entry.getValue();
			switch(key) {
				case "episodeId" -> out.put(key,uuid(key,value).toString());
				case "phase" -> {
					if(!(value instanceof String s)||!(s.equals("START")||s.equals("END"))) throw new InvalidData(key);
					out.put(key,s);
				}
				case "sinceMs","durationMs" -> out.put(key,integer(key,value,0,MAX_MS));
				case "faces" -> {
					if(kind!=EventKind.MULTIPLE_FACES) throw new InvalidData(key);
					out.put(key,(int)integer(key,value,MIN_FACES,MAX_FACES));
				}
				default -> throw new InvalidData("data");
			}
		}
		boolean episode=out.containsKey("episodeId"), phase=out.containsKey("phase");
		if(episode!=phase) throw new InvalidData(episode?"phase":"episodeId");
		if(out.containsKey("sinceMs")&&!"START".equals(out.get("phase"))) throw new InvalidData("sinceMs");
		if(out.containsKey("durationMs")&&!"END".equals(out.get("phase"))) throw new InvalidData("durationMs");
		return Collections.unmodifiableMap(out);
	}
	/** 014-38 y 014-47: la base admitida está dentro de {@code maxBaseDeg} del frente. */
	public static Calibration calibration(Object yaw,Object pitch,double maxBaseDeg) {
		double y=degrees("calibration.baseYawDeg",yaw,maxBaseDeg), p=degrees("calibration.basePitchDeg",pitch,maxBaseDeg);
		return new Calibration(y,p);
	}
	private static double degrees(String field,Object value,double max) {
		if(!(value instanceof Number n)) throw new InvalidData(field);
		double d=n.doubleValue();
		if(!Double.isFinite(d)||Math.abs(d)>max) throw new InvalidData(field);
		return Math.round(d*100)/100.0;
	}
	private static UUID uuid(String field,Object value) {
		if(!(value instanceof String s)||s.length()!=36) throw new InvalidData(field);
		try { return UUID.fromString(s); } catch(IllegalArgumentException e) { throw new InvalidData(field); }
	}
	private static long integer(String field,Object value,long min,long max) {
		if(!(value instanceof Number n)) throw new InvalidData(field);
		long l;
		if(n instanceof Integer||n instanceof Long||n instanceof Short||n instanceof Byte) l=n.longValue();
		else if(n instanceof java.math.BigInteger b&&b.bitLength()<63) l=b.longValue();
		else {
			double d=n.doubleValue();
			if(!Double.isFinite(d)||d!=Math.rint(d)||Math.abs(d)>Long.MAX_VALUE/2) throw new InvalidData(field);
			l=(long)d;
		}
		if(l<min||l>max) throw new InvalidData(field);
		return l;
	}
}
