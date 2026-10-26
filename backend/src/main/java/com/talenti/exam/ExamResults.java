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
	record Result(UUID attemptId,UUID versionId,String status,String gradingStatus,String closedReason,
			BigDecimal score100,BigDecimal score20,BigDecimal pendingPoints,String scoringVersion,
			List<Question> questions,List<Event> events,boolean incomplete) { }
	Optional<Result> forApplication(UUID applicationId);
}
