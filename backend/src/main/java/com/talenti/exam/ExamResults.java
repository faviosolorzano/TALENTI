package com.talenti.exam;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Lectura para la futura empresa. No incluye recomendación de contratación ni clasificación. */
public interface ExamResults {
	record Question(UUID id,String type,String prompt,String choiceId,String text,String code,String gradeStatus,
			BigDecimal points,String detailJson) {
		@Override public String toString() { return "ExamResultQuestion[id="+id+"]"; }
	}
	record Event(String kind,Instant occurredAt,Instant clientOccurredAt,String dataJson) { }
	/** Por tipo de hecho: cuántas veces y cuánto duró en total (014-57). */
	record KindTotal(String kind,int count,long totalDurationMs) { }
	/** Un episodio con inicio y fin (hora del servidor); fin y duración nulos si el examen terminó antes. */
	record Episode(String kind,UUID episodeId,Instant startedAt,Instant endedAt,Long sinceMs,Long durationMs,UUID snapshotId) { }
	record Snapshot(UUID id,Instant takenAt,String reason,UUID eventClientId) { }
	/**
	 * Supervisión del examen (014-57): hechos, nunca un puntaje ni una sospecha. {@code label} es el rótulo que debe
	 * acompañarlos siempre. {@code supervised} es falso en intentos con reglas v1.
	 */
	record Proctoring(String noticeVersion,boolean supervised,String proctoringVersion,String detection,String calibrationJson,
			String thresholdsJson,List<KindTotal> totals,List<Episode> episodes,List<Snapshot> snapshots,String label) { }
	record Result(UUID attemptId,UUID versionId,String status,String gradingStatus,String closedReason,
			BigDecimal score100,BigDecimal score20,BigDecimal pendingPoints,String scoringVersion,
			List<Question> questions,List<Event> events,boolean incomplete,Proctoring proctoring) {
		public Result withProctoring(Proctoring value) {
			return new Result(attemptId,versionId,status,gradingStatus,closedReason,score100,score20,pendingPoints,scoringVersion,questions,events,incomplete,value);
		}
	}
	Optional<Result> forApplication(UUID applicationId);
}
