package com.talenti.exam.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Seguridad y supervisión del examen (spec 014, enmienda 1, E1.3). Los umbrales viajan al navegador, que decide los
 * episodios; el servidor solo guarda hechos y la configuración entregada (principio 11). Ningún valor cierra el examen.
 */
@ConfigurationProperties("talenti.exam.proctoring")
public record ProctoringProperties(@DefaultValue("true") boolean enabled,
		@DefaultValue("60s") Duration snapshotInterval, @DefaultValue("150KB") DataSize snapshotMaxBytes,
		@DefaultValue("640") int snapshotMaxSide, @DefaultValue("150") int maxSnapshotsPerAttempt,
		@DefaultValue("90d") Duration retention, @DefaultValue Storage storage,
		@DefaultValue("30") int eventsPerMinute, @DefaultValue("24h") Duration purgeInterval,
		@DefaultValue("1h") Duration orphanGrace, @DefaultValue("thresholds-v1") String thresholdsVersion,
		@DefaultValue Thresholds thresholds) {
	/** El CHECK de exam_snapshot.byte_size: 150 KB. */
	public static final long MAX_STORED_BYTES=153_600;
	public ProctoringProperties {
		if(snapshotInterval.compareTo(Duration.ofSeconds(10))<0||snapshotMaxBytes.toBytes()<1024||snapshotMaxBytes.toBytes()>MAX_STORED_BYTES
				||snapshotMaxSide<64||snapshotMaxSide>640||maxSnapshotsPerAttempt<1||maxSnapshotsPerAttempt>1000
				||retention.isNegative()||retention.isZero()||eventsPerMinute<1||eventsPerMinute>600
				||purgeInterval.isNegative()||purgeInterval.isZero()||orphanGrace.isNegative()
				||thresholdsVersion==null||!thresholdsVersion.matches("[a-z0-9.-]{1,20}"))
			throw new IllegalArgumentException("Configuración de supervisión del examen inválida");
	}
	public record Storage(@DefaultValue("local") String type, @DefaultValue("/var/lib/talenti/exam-snapshots") String path) {
		public Storage {
			if(!type.equals("local")&&!type.equals("memory")) throw new IllegalArgumentException("Almacenamiento de fotos del examen inválido");
		}
	}
	/** 014-46 a 014-50. Ángulos en grados, tiempos como duración; al navegador viajan en milisegundos. */
	public record Thresholds(@DefaultValue("5") int faceFps, @DefaultValue("2") int objectFps,
			@DefaultValue("3") int degradedFaceFps, @DefaultValue("1") int degradedObjectFps,
			@DefaultValue("60ms") Duration frameBudget, @DefaultValue("3s") Duration calibration,
			@DefaultValue("10") int calibrationMinFrames, @DefaultValue("35") double maxBaseDeg,
			@DefaultValue("0.3") double smoothingAlpha, @DefaultValue("30") double yawDeg,
			@DefaultValue("25") double pitchUpDeg, @DefaultValue("35") double pitchDownDeg,
			@DefaultValue("2s") Duration sustain, @DefaultValue("45") double abruptDeg,
			@DefaultValue("500ms") Duration abruptWindow, @DefaultValue("1s") Duration abruptSustain,
			@DefaultValue("5") double hysteresisDeg, @DefaultValue("500ms") Duration endHold,
			@DefaultValue("3s") Duration faceMissing, @DefaultValue("2s") Duration multiFace,
			@DefaultValue("1s") Duration episodeEnd, @DefaultValue("0.6") double faceScore,
			@DefaultValue("0.6") double phoneScore, @DefaultValue("5") int phoneHits, @DefaultValue("8") int phoneWindow,
			@DefaultValue("30s") Duration noticeCooldown) {
		public Thresholds {
			if(faceFps<1||objectFps<1||degradedFaceFps<1||degradedObjectFps<1||degradedFaceFps>faceFps||degradedObjectFps>objectFps
					||calibrationMinFrames<1||maxBaseDeg<=0||maxBaseDeg>90||smoothingAlpha<=0||smoothingAlpha>1
					||yawDeg<=0||pitchUpDeg<=0||pitchDownDeg<=0||abruptDeg<=0||hysteresisDeg<0
					||faceScore<=0||faceScore>1||phoneScore<=0||phoneScore>1||phoneHits<1||phoneWindow<phoneHits
					||positive(frameBudget,calibration,sustain,abruptWindow,abruptSustain,endHold,faceMissing,multiFace,episodeEnd,noticeCooldown))
				throw new IllegalArgumentException("Umbrales de supervisión del examen inválidos");
		}
		private static boolean positive(Duration... values) {
			for(var v:values) if(v==null||v.isNegative()||v.isZero()) return true;
			return false;
		}
	}
	/** Lo que recibe el navegador y se guarda en el intento al empezar (014-38, 014-46). */
	public Map<String,Object> clientThresholds() {
		var t=thresholds; var m=new LinkedHashMap<String,Object>();
		m.put("version",thresholdsVersion);
		m.put("faceFps",t.faceFps()); m.put("objectFps",t.objectFps());
		m.put("degradedFaceFps",t.degradedFaceFps()); m.put("degradedObjectFps",t.degradedObjectFps());
		m.put("frameBudgetMs",t.frameBudget().toMillis()); m.put("calibrationMs",t.calibration().toMillis());
		m.put("calibrationMinFrames",t.calibrationMinFrames()); m.put("maxBaseDeg",t.maxBaseDeg());
		m.put("smoothingAlpha",t.smoothingAlpha()); m.put("yawDeg",t.yawDeg());
		m.put("pitchUpDeg",t.pitchUpDeg()); m.put("pitchDownDeg",t.pitchDownDeg());
		m.put("sustainMs",t.sustain().toMillis()); m.put("abruptDeg",t.abruptDeg());
		m.put("abruptWindowMs",t.abruptWindow().toMillis()); m.put("abruptSustainMs",t.abruptSustain().toMillis());
		m.put("hysteresisDeg",t.hysteresisDeg()); m.put("endHoldMs",t.endHold().toMillis());
		m.put("faceMissingMs",t.faceMissing().toMillis()); m.put("multiFaceMs",t.multiFace().toMillis());
		m.put("episodeEndMs",t.episodeEnd().toMillis()); m.put("faceScore",t.faceScore());
		m.put("phoneScore",t.phoneScore()); m.put("phoneHits",t.phoneHits()); m.put("phoneWindow",t.phoneWindow());
		m.put("noticeCooldownMs",t.noticeCooldown().toMillis());
		return java.util.Collections.unmodifiableMap(m);
	}
}
