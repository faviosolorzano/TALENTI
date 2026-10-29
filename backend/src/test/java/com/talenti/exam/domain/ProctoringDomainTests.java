package com.talenti.exam.domain;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.talenti.exam.domain.AttemptRules.EventKind;
import com.talenti.exam.domain.ProctoringRules.InvalidData;

/** Reglas puras de la enmienda 1 de la spec 014. */
class ProctoringDomainTests {
	private static final String EP="0b8e7c34-2f6a-4d0e-9a51-3c1f2e4d5a6b";

	@Test void t014_51_acceptsOnlyTheClosedListOfEpisodeFields() {
		assertThat(ProctoringRules.eventData(EventKind.GAZE_AWAY,Map.of("episodeId",EP,"phase","START","sinceMs",2100)))
				.containsEntry("episodeId",EP).containsEntry("phase","START").containsEntry("sinceMs",2100L);
		assertThat(ProctoringRules.eventData(EventKind.MULTIPLE_FACES,Map.of("episodeId",EP,"phase","END","durationMs",3_600_000L,"faces",2)))
				.containsEntry("faces",2).containsEntry("durationMs",3_600_000L);
		assertThat(ProctoringRules.eventData(EventKind.PHONE_VISIBLE,Map.of("episodeId",EP,"phase","END","durationMs",1500.0))).containsEntry("durationMs",1500L);
		assertThat(ProctoringRules.eventData(EventKind.COPY_ATTEMPT,null)).isEmpty();
		assertThat(ProctoringRules.eventData(EventKind.CAMERA_LOST,Map.of())).isEmpty();
	}
	@Test void t014_51_rejectsUnknownKeysWrongValuesAndOutOfRangeNumbers() {
		List<Map.Entry<EventKind,Map<String,Object>>> bad=List.of(
				Map.entry(EventKind.GAZE_AWAY,Map.of("episodeId",EP,"phase","START","image","base64")),
				Map.entry(EventKind.GAZE_AWAY,Map.of("episodeId",EP,"phase","MIDDLE")),
				Map.entry(EventKind.GAZE_AWAY,Map.of("episodeId","no-es-uuid","phase","START")),
				Map.entry(EventKind.GAZE_AWAY,Map.of("episodeId",EP)),
				Map.entry(EventKind.GAZE_AWAY,Map.of("phase","END","durationMs",10)),
				Map.entry(EventKind.GAZE_AWAY,Map.of("episodeId",EP,"phase","START","durationMs",10)),
				Map.entry(EventKind.GAZE_AWAY,Map.of("episodeId",EP,"phase","END","sinceMs",10)),
				Map.entry(EventKind.GAZE_AWAY,Map.of("episodeId",EP,"phase","END","durationMs",3_600_001)),
				Map.entry(EventKind.GAZE_AWAY,Map.of("episodeId",EP,"phase","END","durationMs",-1)),
				Map.entry(EventKind.GAZE_AWAY,Map.of("episodeId",EP,"phase","END","durationMs",10.5)),
				Map.entry(EventKind.GAZE_AWAY,Map.of("episodeId",EP,"phase","END","durationMs","10")),
				Map.entry(EventKind.GAZE_AWAY,Map.of("episodeId",EP,"phase","START","faces",2)),
				Map.entry(EventKind.MULTIPLE_FACES,Map.of("episodeId",EP,"phase","START","faces",1)),
				Map.entry(EventKind.MULTIPLE_FACES,Map.of("episodeId",EP,"phase","START","faces",11)),
				Map.entry(EventKind.COPY_ATTEMPT,Map.of("episodeId",EP,"phase","START")),
				Map.entry(EventKind.SCREEN_LEFT,Map.of("durationMs",5)));
		for(var c:bad) assertThatThrownBy(()->ProctoringRules.eventData(c.getKey(),c.getValue())).as(c.toString()).isInstanceOf(InvalidData.class);
	}
	@Test void t014_52_onlyScreenLeftChangesTheAttempt() {
		for(var kind:EventKind.values()) assertThat(ProctoringRules.changesAttempt(kind)).as(kind.name()).isEqualTo(kind==EventKind.SCREEN_LEFT);
		for(var kind:EventKind.values()) if(kind!=EventKind.SCREEN_LEFT) {
			assertThat(AttemptRules.screenRule(kind,0)).isEqualTo(AttemptRules.ScreenRule.NONE);
			assertThat(AttemptRules.screenRule(kind,1)).isEqualTo(AttemptRules.ScreenRule.NONE);
		}
	}
	@Test void t014_37_38_noticeAndCalibrationRules() {
		assertThat(ProctoringRules.noticeIncludesCamera("v1")).isFalse();
		assertThat(ProctoringRules.noticeIncludesCamera("v2")).isTrue();
		assertThat(ProctoringRules.calibration(20,-35.0,35)).isEqualTo(new ProctoringRules.Calibration(20,-35));
		assertThatThrownBy(()->ProctoringRules.calibration(35.01,0,35)).isInstanceOf(InvalidData.class);
		assertThatThrownBy(()->ProctoringRules.calibration(Double.NaN,0,35)).isInstanceOf(InvalidData.class);
		assertThatThrownBy(()->ProctoringRules.calibration("10",0,35)).isInstanceOf(InvalidData.class);
	}
	@Test void t014_57_summaryCountsDurationsEpisodesAndAssociatedPhotos() {
		Instant t=Instant.parse("2026-10-29T15:00:00Z");
		UUID start=UUID.randomUUID(), ep=UUID.fromString(EP), photo=UUID.randomUUID(), periodic=UUID.randomUUID();
		var facts=List.of(
				new ProctoringSummary.Fact("STARTED",null,t,Map.of()),
				new ProctoringSummary.Fact("GAZE_AWAY",start,t.plusSeconds(10),Map.of("episodeId",EP,"phase","START","sinceMs",2000)),
				new ProctoringSummary.Fact("GAZE_AWAY",UUID.randomUUID(),t.plusSeconds(14),Map.of("episodeId",EP,"phase","END","durationMs",6000)),
				new ProctoringSummary.Fact("CAMERA_LOST",UUID.randomUUID(),t.plusSeconds(20),Map.of()),
				new ProctoringSummary.Fact("CAMERA_RESTORED",UUID.randomUUID(),t.plusSeconds(25),Map.of()),
				new ProctoringSummary.Fact("COPY_ATTEMPT",UUID.randomUUID(),t.plusSeconds(30),Map.of()),
				new ProctoringSummary.Fact("COPY_ATTEMPT",UUID.randomUUID(),t.plusSeconds(31),Map.of()),
				new ProctoringSummary.Fact("FACE_MISSING",UUID.randomUUID(),t.plusSeconds(40),Map.of("episodeId",UUID.randomUUID().toString(),"phase","START","sinceMs",3000)));
		var photos=List.of(new ProctoringSummary.Photo(periodic,t.plusSeconds(60),"PERIODIC",null),
				new ProctoringSummary.Photo(photo,t.plusSeconds(11),"EVENT",start));
		var built=ProctoringSummary.build(facts,photos);
		assertThat(built.totals()).extracting(k->k.kind()+":"+k.count()+":"+k.totalDurationMs())
				.containsExactlyInAnyOrder("GAZE_AWAY:1:6000","CAMERA_LOST:1:5000","CAMERA_RESTORED:1:0","COPY_ATTEMPT:2:0","FACE_MISSING:1:0");
		var gaze=built.episodes().stream().filter(e->e.kind().equals("GAZE_AWAY")).findFirst().orElseThrow();
		assertThat(gaze.episodeId()).isEqualTo(ep); assertThat(gaze.durationMs()).isEqualTo(6000L); assertThat(gaze.sinceMs()).isEqualTo(2000L);
		assertThat(gaze.snapshotId()).isEqualTo(photo);
		var open=built.episodes().stream().filter(e->e.kind().equals("FACE_MISSING")).findFirst().orElseThrow();
		assertThat(open.endedAt()).isNull(); assertThat(open.durationMs()).isNull();
		assertThat(built.snapshots()).hasSize(2);
		assertThat(built.toString()).doesNotContainIgnoringCase("fraud").doesNotContainIgnoringCase("sospech").doesNotContainIgnoringCase("score");
	}
}
