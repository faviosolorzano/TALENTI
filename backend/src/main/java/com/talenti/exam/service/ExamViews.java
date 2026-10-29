package com.talenti.exam.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.talenti.exam.domain.ExamQuestion.*;

/** Solo campos permitidos para el estudiante. No reutiliza entidades ni resultados de empresa. */
public final class ExamViews {
	private ExamViews() { }
	public record VisibleCase(String id,String input,String expectedOutput,String schema,List<SqlFixture> tables,SqlTable expectedTable) {
		@Override public String toString() { return "ExamVisibleCase[id="+id+"]"; }
	}
	public record Question(UUID id,String type,String prompt,String generatedBy,List<Option> options,List<Criterion> rubric,String language,
			String kind,String initialCode,List<VisibleCase> cases) {
		@Override public String toString() { return "ExamStudentQuestion[id="+id+"]"; }
	}
	public record Answer(UUID questionId,String choiceId,String text,String code,long revision,int runCount,Instant updatedAt) {
		@Override public String toString() { return "ExamStudentAnswer[questionId="+questionId+",revision="+revision+"]"; }
	}
	public record Event(String kind,UUID clientEventId,Instant clientOccurredAt,Instant occurredAt) { }
	/**
	 * Supervisión del intento (014-38, E1.4). {@code required}: hay que cumplir los requisitos de {@code start} y tomar
	 * fotos. {@code noticeOutdated}: el intento aceptó un aviso anterior y debe aceptar el vigente con
	 * {@code POST /exam-attempts/{id}/notice}. {@code snapshotInterval} en segundos; los umbrales en ms y grados.
	 */
	public record Proctoring(boolean required,boolean noticeOutdated,String version,String detection,Map<String,Object> calibration,
			long snapshotInterval,long snapshotMaxBytes,int snapshotMaxSide,int maxSnapshots,Map<String,Object> thresholds) { }
	/** 014-43: nombre y apellido del perfil (nulo si no los declaró) y código corto de la postulación. */
	public record Watermark(String name,String code) {
		@Override public String toString() { return "ExamWatermark[code="+code+"]"; }
	}
	public record Attempt(UUID id,UUID applicationId,String status,String gradingStatus,String noticeVersion,String currentNoticeVersion,
			String notice,Instant startedAt,Instant deadlineAt,long remainingSeconds,List<Question> questions,List<Answer> answers,
			List<Event> events,Proctoring proctoring,Watermark watermark) { }
	public record Preparation(String status,int retryAfter) { }
	public record Save(long revision,Instant savedAt) { }
	public record EventReply(String rule,String message,String status) { }
	public record CaseResult(boolean passed,String output,String error,long ms,SqlTable table) {
		@Override public String toString() { return "ExamCaseResult[passed="+passed+",error="+error+"]"; }
	}
	public record Run(List<CaseResult> cases) {
		@Override public String toString() { return "ExamRun[cases="+cases.size()+"]"; }
	}
}
