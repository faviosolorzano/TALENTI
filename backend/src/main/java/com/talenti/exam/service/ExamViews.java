package com.talenti.exam.service;

import java.time.Instant;
import java.util.List;
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
	public record Attempt(UUID id,UUID applicationId,String status,String gradingStatus,String noticeVersion,String notice,
			Instant startedAt,Instant deadlineAt,long remainingSeconds,List<Question> questions,List<Answer> answers,List<Event> events) { }
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
