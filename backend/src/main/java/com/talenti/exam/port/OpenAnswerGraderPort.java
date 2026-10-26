package com.talenti.exam.port;

import java.util.List;
import com.talenti.exam.domain.ExamQuestion.Criterion;

public interface OpenAnswerGraderPort {
	record Request(String question, List<Criterion> rubric, List<String> keyPoints, String answer) {
		@Override public String toString() { return "OpenAnswerRequest[...]"; }
	}
	record Proposed(String criterion, Integer level, String comment, List<String> quotes) {
		@Override public String toString() { return "ProposedCriterion[...]"; }
	}
	List<Proposed> grade(Request request);
	String model();
	String promptVersion();
}
