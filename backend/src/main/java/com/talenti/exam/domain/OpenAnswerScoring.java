package com.talenti.exam.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import com.talenti.exam.port.OpenAnswerGraderPort.Proposed;

/** Los criterios vienen de la pregunta verificada; las propuestas de IA no pueden cambiarlos. */
public final class OpenAnswerScoring {
	private OpenAnswerScoring() { }
	public record CriterionGrade(String criterion, int level, String comment, List<String> quotes) { }
	public record Grade(List<CriterionGrade> criteria, BigDecimal points, String generatedBy, String rubricVersion) {
		public boolean complete() { return points != null; }
	}
	public static Grade validate(ExamQuestion q, String answer, List<Proposed> proposed, Map<String, BigDecimal> weights) {
		Map<String, CriterionGrade> accepted = new LinkedHashMap<>();
		Set<String> allowed = new HashSet<>(q.rubric().stream().map(ExamQuestion.Criterion::id).toList());
		for (Proposed p : proposed == null ? List.<Proposed>of() : proposed) {
			if (p == null || !allowed.contains(p.criterion()) || accepted.containsKey(p.criterion())
					|| p.level() == null || p.level() < 1 || p.level() > 4) continue;
			String comment = p.comment();
			if (comment != null && (comment.length() > 400 || ExamSafety.personalComment(comment))) comment = null;
			List<String> quotes = p.quotes() == null ? List.of() : p.quotes().stream().filter(Objects::nonNull)
					.map(String::strip).filter(s -> !s.isEmpty() && s.length() <= 300 && answer.contains(s)).distinct().limit(3).toList();
			accepted.put(p.criterion(), new CriterionGrade(p.criterion(), p.level(), comment, quotes));
		}
		List<CriterionGrade> ordered = q.rubric().stream().map(c -> accepted.get(c.id())).filter(Objects::nonNull).toList();
		BigDecimal points = null;
		if (ordered.size() == q.rubric().size() && !ordered.isEmpty()) {
			BigDecimal weighted = BigDecimal.ZERO, totalWeight = BigDecimal.ZERO;
			for (CriterionGrade c : ordered) {
				BigDecimal weight = weights.getOrDefault(c.criterion(), BigDecimal.ONE);
				if (weight.signum() <= 0) throw new IllegalArgumentException("Peso de rúbrica inválido");
				totalWeight = totalWeight.add(weight);
				weighted = weighted.add(weight.multiply(BigDecimal.valueOf(c.level())));
			}
			points = q.points().multiply(weighted).divide(totalWeight.multiply(BigDecimal.valueOf(4)), 2, RoundingMode.HALF_UP);
		}
		return new Grade(ordered, points, "AI", "exam-rubric-v1");
	}
}
