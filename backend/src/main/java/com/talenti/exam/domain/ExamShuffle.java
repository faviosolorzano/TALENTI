package com.talenti.exam.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** La semilla persistida reproduce siempre el orden; no cambia los identificadores de opciones. */
public final class ExamShuffle {
	private ExamShuffle() { }
	public record Question(ExamQuestion question, List<ExamQuestion.Option> options) { }
	public static List<Question> order(List<ExamQuestion> questions, long seed) {
		var random = new Random(seed);
		List<Question> result = new ArrayList<>();
		for (ExamQuestion.Type type : ExamQuestion.Type.values()) {
			List<ExamQuestion> block = new ArrayList<>(questions.stream().filter(q -> q.type() == type)
					.sorted(java.util.Comparator.comparingInt(ExamQuestion::slot)).toList());
			Collections.shuffle(block, random);
			for (ExamQuestion q : block) {
				var options = new ArrayList<>(q.options());
				Collections.shuffle(options, random);
				result.add(new Question(q, List.copyOf(options)));
			}
		}
		return List.copyOf(result);
	}
}
