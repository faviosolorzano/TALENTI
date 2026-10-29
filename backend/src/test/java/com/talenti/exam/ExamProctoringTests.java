package com.talenti.exam;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import com.talenti.exam.domain.AttemptStatus;
import com.talenti.exam.persistence.ExamStore;
import com.talenti.exam.snapshot.ExamSnapshotService;
import com.talenti.exam.snapshot.MemorySnapshotStorage;
import com.talenti.exam.snapshot.SnapshotStore;
import com.talenti.matching.MatchingIntegrationTest;
import com.talenti.support.ApiClient;

/** Integración de la enmienda 1 de la spec 014 (seguridad y supervisión), con PostgreSQL real. */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"talenti.demo-data.enabled=true","talenti.exam.runner.mode=simulated"})
class ExamProctoringTests extends MatchingIntegrationTest {
	@Autowired ExamStore store;
	@Autowired SnapshotStore snapshots;
	@Autowired MemorySnapshotStorage storage;
	@Autowired ExamSnapshotService snapshotService;
	@Autowired ExamResults results;
	@Autowired ExamProctoring proctoring;
	private record Fixture(RegisteredStudent student,UUID application,UUID attempt) {
		ApiClient api() { return student.api(); }
		String route() { return "/api/v1/exam-attempts/"+attempt; }
	}
	private final List<UUID> createdVacancies=new ArrayList<>();
	@AfterEach void reset() {
		clock.set(Instant.now());
		createdVacancies.forEach(v->{ jdbc.update("DELETE FROM application WHERE vacancy_id=?",v); deleteVacancy(v); });
		createdVacancies.clear();
	}
	private UUID application(RegisteredStudent student) {
		UUID vacancy=UUID.randomUUID(),application=UUID.randomUUID();
		insertVacancyWithJava("Supervisión "+vacancy,OffsetDateTime.now(),"OPEN",vacancy,true);
		createdVacancies.add(vacancy);
		jdbc.update("INSERT INTO application(id,user_id,vacancy_id,status,submitted_at) VALUES (?,?,?,'SUBMITTED',now())",application,student.id(),vacancy);
		return application;
	}
	private Fixture create() { RegisteredStudent s=registerStudent(); return create(s,application(s)); }
	private Fixture create(RegisteredStudent student,UUID application) {
		long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
		while(true) {
			var r=student.api().post("/api/v1/applications/"+application+"/exam",Map.of("noticeAccepted",true,"noticeVersion","v2"));
			if(r.status()==201) return new Fixture(student,application,UUID.fromString(r.json().get("id").asString()));
			assertThat(r.status()).as(r.body()).isEqualTo(202);
			if(System.nanoTime()>deadline) throw new AssertionError("El conjunto no terminó de prepararse");
			try { Thread.sleep(30); } catch(InterruptedException e) { throw new IllegalStateException(e); }
		}
	}
	private Fixture started() { var f=create(); assertThat(f.api().post(f.route()+"/start",ExamTestSupport.START).status()).isEqualTo(200); return f; }
	private static Map<String,Object> event(String kind) { return event(kind,null); }
	private static Map<String,Object> event(String kind,Map<String,Object> data) {
		var m=new HashMap<String,Object>(Map.of("kind",kind,"clientEventId",UUID.randomUUID().toString(),"clientOccurredAt",Instant.now().toString()));
		if(data!=null) m.put("data",data);
		return m;
	}
	private ApiClient.Response upload(ApiClient api,Fixture f,byte[] image,String reason,String eventClientId) {
		var fields=new LinkedHashMap<String,String>(); fields.put("reason",reason); fields.put("clientTakenAt",Instant.now().toString());
		if(eventClientId!=null) fields.put("eventClientId",eventClientId);
		return api.postMultipart(f.route()+"/snapshots","image","foto.jpg","image/jpeg",image,fields,true);
	}

	@Test void t014_37_noticeV2AndReacceptanceBeforeStart() {
		var s=registerStudent(); UUID app=application(s);
		var old=s.api().post("/api/v1/applications/"+app+"/exam",Map.of("noticeAccepted",true,"noticeVersion","v1"));
		assertThat(old.status()).isEqualTo(409); assertThat(old.json().get("code").asString()).isEqualTo("notice-version-outdated");
		var f=create(s,app);
		var view=f.api().get(f.route()).json();
		assertThat(view.get("currentNoticeVersion").asString()).isEqualTo("v2");
		assertThat(view.get("notice").asString()).contains("Cámara obligatoria","en tu equipo","cada 60 segundos","90 días","nunca se envían a una inteligencia artificial","Ningún aviso de cámara te reprueba");
		// Intento creado con el aviso v1 (antes de la enmienda): debe aceptar el v2 antes de empezar.
		jdbc.update("UPDATE exam_attempt SET notice_version='v1' WHERE id=?",f.attempt());
		assertThat(f.api().get(f.route()).json().get("proctoring").get("noticeOutdated").asBoolean()).isTrue();
		var blocked=f.api().post(f.route()+"/start",ExamTestSupport.START);
		assertThat(blocked.status()).isEqualTo(409); assertThat(blocked.json().get("code").asString()).isEqualTo("notice-version-outdated");
		assertThat(f.api().post(f.route()+"/notice",Map.of("noticeAccepted",true,"noticeVersion","v1")).json().get("code").asString()).isEqualTo("notice-version-outdated");
		assertThat(f.api().post(f.route()+"/notice",Map.of("noticeAccepted",false,"noticeVersion","v2")).status()).isEqualTo(422);
		assertThat(registerStudent().api().post(f.route()+"/notice",Map.of("noticeAccepted",true,"noticeVersion","v2")).status()).isEqualTo(404);
		var accepted=f.api().post(f.route()+"/notice",Map.of("noticeAccepted",true,"noticeVersion","v2"));
		assertThat(accepted.status()).isEqualTo(200); assertThat(accepted.json().get("noticeVersion").asString()).isEqualTo("v2");
		assertThat(store.events(f.attempt())).extracting(ExamStore.EventRow::kind).contains("NOTICE_ACCEPTED");
		assertThat(f.api().post(f.route()+"/start",ExamTestSupport.START).status()).isEqualTo(200);
		assertThat(f.api().post(f.route()+"/notice",Map.of("noticeAccepted",true,"noticeVersion","v2")).status()).isEqualTo(409);
	}
	@Test void t014_37_inProgressV1AttemptKeepsV1RulesWithoutPhotos() {
		var f=create();
		jdbc.update("UPDATE exam_attempt SET notice_version='v1',status='IN_PROGRESS',started_at=?,deadline_at=? WHERE id=?",
				OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC),OffsetDateTime.ofInstant(clock.instant().plusSeconds(3600),ZoneOffset.UTC),f.attempt());
		var view=f.api().get(f.route()).json();
		assertThat(view.get("proctoring").get("required").asBoolean()).isFalse();
		assertThat(f.api().post(f.route()+"/events",event("COPY_ATTEMPT")).json().get("rule").asString()).isEqualTo("NONE");
		var photo=upload(f.api(),f,TestJpegs.jpeg(320,240),"PERIODIC",null);
		assertThat(photo.status()).isEqualTo(409); assertThat(photo.json().get("code").asString()).isEqualTo("proctoring-not-active");
	}
	@Test void t014_38_startRequiresCameraAndFullscreenAndStoresDeliveredConfig() {
		var f=create(); String start=f.route()+"/start";
		var before=f.api().get(f.route()).json().get("proctoring");
		assertThat(before.get("required").asBoolean()).isTrue();
		assertThat(before.get("snapshotInterval").asLong()).isEqualTo(60);
		assertThat(before.get("thresholds").get("yawDeg").asDouble()).isEqualTo(30);
		assertThat(before.get("thresholds").get("sustainMs").asLong()).isEqualTo(2000);
		for(Object body:new Object[]{null,Map.of(),Map.of("cameraReady",true,"fullscreen",false,"detection","AVAILABLE"),
				Map.of("cameraReady",false,"fullscreen",true,"detection","UNAVAILABLE")}) {
			var r=f.api().post(start,body);
			assertThat(r.status()).as(String.valueOf(body)).isEqualTo(422);
			assertThat(r.json().get("code").asString()).isEqualTo("proctoring-required");
		}
		assertThat(f.api().post(start,Map.of("cameraReady",true,"fullscreen",true,"detection","MAYBE")).status()).isEqualTo(422);
		assertThat(f.api().post(start,Map.of("cameraReady",true,"fullscreen",true,"detection","AVAILABLE")).status()).isEqualTo(422);
		assertThat(f.api().post(start,Map.of("cameraReady",true,"fullscreen",true,"detection","AVAILABLE","calibration",Map.of("baseYawDeg",40,"basePitchDeg",0))).status()).isEqualTo(422);
		assertThat(f.api().post(start,Map.of("cameraReady",true,"fullscreen",true,"detection","AVAILABLE","calibration",Map.of("baseYawDeg",1,"basePitchDeg",0,"face","x"))).status()).isEqualTo(422);
		assertThat(store.findAttempt(f.attempt()).orElseThrow().status()).isEqualTo(AttemptStatus.NOT_STARTED);
		var ok=f.api().post(start,ExamTestSupport.START);
		assertThat(ok.status()).isEqualTo(200);
		var a=store.findAttempt(f.attempt()).orElseThrow();
		assertThat(a.proctoringVersion()).isEqualTo("thresholds-v1"); assertThat(a.detectionStatus()).isEqualTo("AVAILABLE");
		assertThat(a.calibration()).contains("baseYawDeg").contains("4.5"); assertThat(a.proctoringConfig()).contains("\"yawDeg\"");
		var p=ok.json().get("proctoring");
		assertThat(p.get("version").asString()).isEqualTo("thresholds-v1");
		assertThat(p.get("calibration").get("basePitchDeg").asDouble()).isEqualTo(-3.0);
		assertThat(ok.json().get("watermark").get("code").asString()).isEqualTo("T-"+f.application().toString().substring(0,6).toUpperCase());
	}
	@Test void t014_40_43_unavailableDetectionIsAFactAndWatermarkUsesProfileName() {
		var s=registerStudent();
		var about=s.api().put("/api/v1/profile/about",Map.of("firstNames","Ana María","lastNames","Pérez Gómez"));
		assertThat(about.status()).as(about.body()).isEqualTo(200);
		var f=create(s,application(s));
		var r=f.api().post(f.route()+"/start",Map.of("cameraReady",true,"fullscreen",true,"detection","UNAVAILABLE"));
		assertThat(r.status()).isEqualTo(200);
		assertThat(r.json().get("watermark").get("name").asString()).isEqualTo("Ana Pérez");
		assertThat(store.events(f.attempt())).extracting(ExamStore.EventRow::kind).contains("DETECTION_UNAVAILABLE");
		assertThat(store.findAttempt(f.attempt()).orElseThrow().status()).isEqualTo(AttemptStatus.IN_PROGRESS);
	}
	@Test void t014_51_eventDataIsClosedAndLimitedPerMinute() {
		var f=started(); String path=f.route()+"/events"; String ep=UUID.randomUUID().toString();
		var startEv=f.api().post(path,event("GAZE_AWAY",Map.of("episodeId",ep,"phase","START","sinceMs",2100)));
		assertThat(startEv.status()).isEqualTo(200); assertThat(startEv.json().get("rule").asString()).isEqualTo("NONE");
		assertThat(f.api().post(path,event("MULTIPLE_FACES",Map.of("episodeId",UUID.randomUUID().toString(),"phase","START","sinceMs",2000,"faces",2))).status()).isEqualTo(200);
		for(var bad:List.of(event("GAZE_AWAY",Map.of("episodeId",ep,"phase","END","durationMs",3_600_001)),
				event("GAZE_AWAY",Map.of("episodeId",ep,"phase","END","image","x")),
				event("COPY_ATTEMPT",Map.of("episodeId",ep,"phase","START")),
				event("PHONE_VISIBLE",Map.of("episodeId",ep,"phase","START","faces",3)),
				event("FRAUD_SCORE"))) {
			var r=f.api().post(path,bad); assertThat(r.status()).as(bad.toString()).isEqualTo(422);
		}
		var stored=store.events(f.attempt()).stream().filter(e->e.kind().equals("GAZE_AWAY")).findFirst().orElseThrow();
		assertThat(stored.dataJson()).contains(ep).contains("2100");
		for(int i=2;i<30;i++) assertThat(f.api().post(path,event(i%2==0?"COPY_ATTEMPT":"CONTEXT_MENU")).status()).isEqualTo(200);
		var limited=f.api().post(path,event("PRINT_ATTEMPT"));
		assertThat(limited.status()).isEqualTo(429);
		// Salir de la pantalla nunca se rechaza por el tope: aplica la regla de 014-15.
		assertThat(f.api().post(path,event("SCREEN_LEFT")).json().get("rule").asString()).isEqualTo("WARNING");
		clock.advance(Duration.ofSeconds(61));
		assertThat(f.api().post(path,event("PRINT_ATTEMPT")).status()).isEqualTo(200);
	}
	@Test void t014_52_randomCameraEventsNeverCloseOrChangeTheAttempt() {
		var f=started(); var random=new Random(52);
		String[] kinds={"FACE_MISSING","MULTIPLE_FACES","PHONE_VISIBLE","GAZE_AWAY","CAMERA_LOST","CAMERA_RESTORED","PRINT_ATTEMPT",
				"CONTEXT_MENU","DETECTION_UNAVAILABLE","COPY_ATTEMPT","PASTE_ATTEMPT","FOCUS_LOST","CONNECTION_LOST","CONNECTION_RESTORED","SCREEN_RETURNED"};
		for(int i=0;i<120;i++) {
			if(i%25==24) clock.advance(Duration.ofSeconds(61));
			String kind=kinds[random.nextInt(kinds.length)];
			Map<String,Object> data=null;
			if(Set.of("FACE_MISSING","MULTIPLE_FACES","PHONE_VISIBLE","GAZE_AWAY").contains(kind)) {
				boolean start=random.nextBoolean(); data=new HashMap<>(Map.of("episodeId",UUID.randomUUID().toString(),"phase",start?"START":"END"));
				data.put(start?"sinceMs":"durationMs",random.nextInt(3_600_001));
				if(kind.equals("MULTIPLE_FACES")) data.put("faces",2+random.nextInt(9));
			}
			var r=f.api().post(f.route()+"/events",event(kind,data));
			assertThat(r.status()).as(kind).isEqualTo(200);
			assertThat(r.json().get("rule").asString()).isEqualTo("NONE");
			assertThat(r.json().get("status").asString()).isEqualTo("IN_PROGRESS");
		}
		var a=store.findAttempt(f.attempt()).orElseThrow();
		assertThat(a.status()).isEqualTo(AttemptStatus.IN_PROGRESS); assertThat(a.screenLeftCount()).isZero(); assertThat(a.score100()).isNull();
	}
	@Test void t014_53_54_55_snapshotsAreValidatedCleanedAndOnlyForTheOwner(CapturedOutput output) {
		var f=started(); var api=f.api();
		String mark="marca"+UUID.randomUUID().toString().replace("-","");
		var created=api.postMultipart(f.route()+"/snapshots","image",mark+".jpg","image/png",TestJpegs.withExif(TestJpegs.jpeg(640,480),mark),
				Map.of("reason","START","clientTakenAt",Instant.now().toString()),true);
		assertThat(created.status()).as(created.body()).isEqualTo(201);
		UUID id=UUID.fromString(created.json().get("id").asString());
		assertThat(created.json().get("takenAt").asString()).isNotBlank();
		var row=snapshots.find(f.attempt(),id).orElseThrow();
		assertThat(row.storageKey()).isEqualTo(f.attempt()+"/"+id+".jpg");
		assertThat(row.width()).isEqualTo(640); assertThat(row.byteSize()).isLessThanOrEqualTo(153_600);
		byte[] stored=storage.read(row.storageKey()).orElseThrow();
		assertThat(TestJpegs.contains(stored,mark)).isFalse(); assertThat(TestJpegs.contains(stored,"Exif")).isFalse();
		var list=api.get(f.route()+"/snapshots");
		assertThat(list.status()).isEqualTo(200); assertThat(list.header("Cache-Control")).isEqualTo("no-store");
		assertThat(list.json()).hasSize(1); assertThat(list.json().get(0).get("reason").asString()).isEqualTo("START");
		var image=api.get(f.route()+"/snapshots/"+id);
		assertThat(image.status()).isEqualTo(200); assertThat(image.header("Content-Type")).startsWith("image/jpeg");
		assertThat(image.header("Cache-Control")).isEqualTo("no-store");
		var other=registerStudent().api();
		assertThat(other.get(f.route()+"/snapshots").status()).isEqualTo(404);
		assertThat(other.get(f.route()+"/snapshots/"+id).status()).isEqualTo(404);
		assertThat(upload(other,f,TestJpegs.jpeg(64,48),"PERIODIC",null).status()).isEqualTo(404);
		// Validación: tipo por bytes, tamaño, medidas, decodificación y campos.
		var png=upload(api,f,TestJpegs.png(64,48),"PERIODIC",null);
		assertThat(png.status()).isEqualTo(415); assertThat(png.json().get("code").asString()).isEqualTo("snapshot-not-jpeg");
		var big=upload(api,f,TestJpegs.fakeJpeg(150*1024+1),"PERIODIC",null);
		assertThat(big.status()).isEqualTo(413); assertThat(big.json().get("code").asString()).isEqualTo("snapshot-too-large");
		var huge=upload(api,f,TestJpegs.write(new java.awt.image.BufferedImage(2000,2000,java.awt.image.BufferedImage.TYPE_INT_RGB),"jpg"),"PERIODIC",null);
		assertThat(huge.status()).isEqualTo(422); assertThat(huge.json().get("code").asString()).isEqualTo("snapshot-dimensions");
		var broken=upload(api,f,TestJpegs.fakeJpeg(5000),"PERIODIC",null);
		assertThat(broken.status()).isEqualTo(422); assertThat(broken.json().get("code").asString()).isEqualTo("snapshot-unreadable");
		assertThat(upload(api,f,TestJpegs.jpeg(64,48),"EVENT",null).status()).isEqualTo(422);
		assertThat(upload(api,f,TestJpegs.jpeg(64,48),"PERIODIC",UUID.randomUUID().toString()).status()).isEqualTo(422);
		assertThat(upload(api,f,TestJpegs.jpeg(64,48),"SELFIE",null).status()).isEqualTo(422);
		var noCsrf=api.postMultipart(f.route()+"/snapshots","image","f.jpg","image/jpeg",TestJpegs.jpeg(64,48),Map.of("reason","PERIODIC","clientTakenAt",Instant.now().toString()),false);
		assertThat(noCsrf.status()).isEqualTo(403);
		assertThat(snapshots.count(f.attempt())).isEqualTo(1);
		for(var r:List.of(png,big,huge,broken,created)) assertThat(r.body()).doesNotContain(mark);
		assertThat(output.getAll()).doesNotContain(mark);
	}
	@Test void t014_54_limitPerAttemptAndClosedAttempt() {
		var f=started();
		for(int i=0;i<149;i++) {
			UUID id=UUID.randomUUID();
			snapshots.insert(new SnapshotStore.Row(id,f.attempt(),"PERIODIC",null,f.attempt()+"/"+id+".jpg",10,10,10,"0".repeat(64),clock.instant(),clock.instant(),clock.instant().plus(Duration.ofDays(90))));
		}
		assertThat(upload(f.api(),f,TestJpegs.jpeg(64,48),"PERIODIC",null).status()).isEqualTo(201);
		var limit=upload(f.api(),f,TestJpegs.jpeg(64,48),"PERIODIC",null);
		assertThat(limit.status()).isEqualTo(429); assertThat(limit.json().get("code").asString()).isEqualTo("snapshot-limit");
		assertThat(f.api().post(f.route()+"/submit",null).status()).isEqualTo(200);
		var closed=upload(f.api(),f,TestJpegs.jpeg(64,48),"PERIODIC",null);
		assertThat(closed.status()).isEqualTo(409); assertThat(closed.json().get("code").asString()).isEqualTo("exam-closed");
		assertThat(f.api().get(f.route()+"/snapshots").json()).hasSize(150);
	}
	@Test void t014_56_purgeNinetyDaysAfterEnd() {
		var f=started();
		var created=upload(f.api(),f,TestJpegs.jpeg(320,240),"PERIODIC",null); assertThat(created.status()).isEqualTo(201);
		UUID id=UUID.fromString(created.json().get("id").asString()); String key=f.attempt()+"/"+id+".jpg";
		clock.advance(Duration.ofMinutes(10));
		assertThat(f.api().post(f.route()+"/submit",null).status()).isEqualTo(200);
		Instant ended=store.findAttempt(f.attempt()).orElseThrow().endedAt();
		assertThat(snapshots.find(f.attempt(),id).orElseThrow().purgeAfter()).isEqualTo(ended.plus(Duration.ofDays(90)));
		clock.advance(Duration.ofDays(89)); snapshotService.purgeExpired();
		assertThat(storage.contains(key)).isTrue();
		clock.advance(Duration.ofDays(2)); assertThat(snapshotService.purgeExpired()).isGreaterThanOrEqualTo(1);
		assertThat(snapshots.find(f.attempt(),id)).isEmpty(); assertThat(storage.contains(key)).isFalse();
		assertThat(f.api().get(f.route()+"/snapshots/"+id).status()).isEqualTo(404);
	}
	@Test void t014_56_withdrawalAndAccountDeletionRemovePhotos() {
		var f=started();
		var created=upload(f.api(),f,TestJpegs.jpeg(320,240),"START",null); assertThat(created.status()).isEqualTo(201);
		String key=f.attempt()+"/"+created.json().get("id").asString()+".jpg";
		assertThat(f.api().post("/api/v1/applications/"+f.application()+"/withdraw",null).status()).isEqualTo(200);
		assertThat(snapshots.forAttempt(f.attempt())).isEmpty(); assertThat(storage.contains(key)).isFalse();
		assertThat(store.findAttempt(f.attempt()).orElseThrow().status()).isEqualTo(AttemptStatus.CLOSED_WITHDRAWN);

		var g=started();
		var other=upload(g.api(),g,TestJpegs.jpeg(320,240),"START",null); assertThat(other.status()).isEqualTo(201);
		String otherKey=g.attempt()+"/"+other.json().get("id").asString()+".jpg";
		jdbc.update("DELETE FROM app_user WHERE id=?",g.student().id());
		assertThat(store.findAttempt(g.attempt())).isEmpty();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM exam_snapshot WHERE attempt_id=?",Integer.class,g.attempt())).isZero();
		snapshotService.sweepOrphans();
		assertThat(storage.contains(otherKey)).isTrue(); // margen de una hora para subidas en curso
		clock.advance(Duration.ofHours(2)); snapshotService.sweepOrphans();
		assertThat(storage.contains(otherKey)).isFalse();
	}
	@Test void t014_57_companyResultsSummariseFactsAndOpenSnapshots() {
		var f=started(); var api=f.api(); String path=f.route()+"/events";
		String ep=UUID.randomUUID().toString(); var startEvent=event("GAZE_AWAY",Map.of("episodeId",ep,"phase","START","sinceMs",2000));
		assertThat(api.post(path,startEvent).status()).isEqualTo(200);
		var photo=upload(api,f,TestJpegs.jpeg(320,240),"EVENT",startEvent.get("clientEventId").toString()); assertThat(photo.status()).isEqualTo(201);
		UUID photoId=UUID.fromString(photo.json().get("id").asString());
		clock.advance(Duration.ofSeconds(5));
		assertThat(api.post(path,event("GAZE_AWAY",Map.of("episodeId",ep,"phase","END","durationMs",7000))).status()).isEqualTo(200);
		assertThat(api.post(path,event("CAMERA_LOST")).status()).isEqualTo(200);
		clock.advance(Duration.ofSeconds(4));
		assertThat(api.post(path,event("CAMERA_RESTORED")).status()).isEqualTo(200);
		var p=results.forApplication(f.application()).orElseThrow().proctoring();
		assertThat(p.supervised()).isTrue(); assertThat(p.noticeVersion()).isEqualTo("v2"); assertThat(p.detection()).isEqualTo("AVAILABLE");
		assertThat(p.calibrationJson()).contains("baseYawDeg"); assertThat(p.thresholdsJson()).contains("yawDeg");
		assertThat(p.totals()).extracting(t->t.kind()+":"+t.count()+":"+t.totalDurationMs()).contains("GAZE_AWAY:1:7000","CAMERA_LOST:1:4000");
		var gaze=p.episodes().stream().filter(e->e.kind().equals("GAZE_AWAY")).findFirst().orElseThrow();
		assertThat(gaze.snapshotId()).isEqualTo(photoId); assertThat(gaze.durationMs()).isEqualTo(7000L);
		assertThat(p.snapshots()).extracting(ExamResults.Snapshot::reason).containsExactly("EVENT");
		assertThat(p.label()).isEqualTo("Hechos registrados durante el examen; no indican fraude. La decisión es de la empresa.");
		var image=proctoring.openSnapshot(f.application(),photoId).orElseThrow();
		assertThat(image.jpeg()).isNotEmpty(); assertThat(image.reason()).isEqualTo("EVENT");
		assertThat(proctoring.openSnapshot(UUID.randomUUID(),photoId)).isEmpty();
		assertThat(store.findAttempt(f.attempt()).orElseThrow().status()).isEqualTo(AttemptStatus.IN_PROGRESS);
	}
}
