package com.talenti.exam.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import com.talenti.exam.port.CodeExecutionPort.Result;

/** Todas las notas y semejanzas salen del código; la IA solo propone criterios para las libres. */
public final class ExamScoring {
	public static final String VERSION = "exam-scoring-v1";
	public record Rules(double fullThreshold, double zeroThreshold, double nonRunningCap, double sqlColumnsWeight) {
		public Rules {
			if (zeroThreshold < 0 || fullThreshold > 1 || zeroThreshold >= fullThreshold || nonRunningCap < 0
					|| nonRunningCap >= fullThreshold || sqlColumnsWeight < 0 || sqlColumnsWeight > 1) throw new IllegalArgumentException("Umbrales de examen inválidos");
		}
	}
	public record CodeGrade(boolean ran, int passed, int total, double resultSimilarity, double structuralSimilarity,
			double similarity, BigDecimal points, String rule, List<com.talenti.exam.port.CodeExecutionPort.Error> errors,
			Rules thresholds, String scoringVersion) { }
	private final Rules rules;
	public ExamScoring(Rules rules) { this.rules = rules; }
	public static BigDecimal choice(ExamQuestion q, String choiceId) {
		return Objects.equals(q.correctId(), choiceId) && choiceId != null ? q.points() : BigDecimal.ZERO.setScale(2);
	}
	public BigDecimal points(double similarity, BigDecimal maximum) {
		if (!Double.isFinite(similarity) || similarity < rules.zeroThreshold()) return BigDecimal.ZERO.setScale(2);
		if (similarity >= rules.fullThreshold()) return maximum.setScale(2, RoundingMode.HALF_UP);
		return maximum.multiply(BigDecimal.valueOf(similarity)).setScale(2, RoundingMode.HALF_UP);
	}
	public CodeGrade code(ExamQuestion q, String answer, List<Result> results) {
		if (results.size() != q.cases().size()) throw new IllegalArgumentException("Faltan resultados de ejecución");
		boolean empty = answer == null || answer.isBlank() || answer.strip().equals(Objects.toString(q.initialCode(), "").strip());
		boolean ran = results.stream().anyMatch(Result::completed);
		int passed = 0; double resultScore = 0;
		for (int i = 0; i < results.size(); i++) {
			Result result = results.get(i); var c = q.cases().get(i); double similarity = 0;
			if (result.completed()) {
				if (q.language() == ExamQuestion.Language.SQL && c.expectedTable() != null && result.table() != null)
					similarity = SqlSimilarity.compare(c.expectedTable(), result.table(), q.orderMatters(), rules.sqlColumnsWeight());
				else if (q.language() != ExamQuestion.Language.SQL && normalize(result.output()).equals(normalize(c.expectedOutput()))) similarity = 1;
			}
			if (similarity >= 1 - 1e-9) passed++;
			resultScore += similarity;
		}
		resultScore = results.isEmpty() ? 0 : resultScore / results.size();
		double structural = empty || ran ? 0 : structural(q, answer);
		double similarity = empty ? 0 : ran ? resultScore : Math.min(structural, rules.nonRunningCap());
		return new CodeGrade(ran, passed, results.size(), resultScore, structural, similarity, points(similarity, q.points()),
				empty ? "EMPTY_OR_INITIAL" : ran ? "RESULTS" : "STRUCTURAL_CAPPED",
				results.stream().map(Result::error).toList(), rules, VERSION);
	}
	private static double structural(ExamQuestion q, String answer) {
		Set<String> known = new HashSet<>(CodeTokens.raw(q.prompt(), q.language()));
		known.addAll(CodeTokens.raw(q.initialCode(), q.language()));
		q.cases().forEach(c -> known.addAll(CodeTokens.raw(c.setupSql(), q.language())));
		var a = CodeTokens.normalized(answer, q.language(), known);
		var initial = CodeTokens.normalized(q.initialCode(), q.language(), known);
		double best = 0;
		for (String reference : q.references()) {
			var ref = CodeTokens.normalized(reference, q.language(), known);
			int d = CodeTokens.distance(a, ref);
			if (q.kind() == ExamQuestion.CodeKind.WRITE) best = Math.max(best, 1 - (double) d / Math.max(1, Math.max(a.size(), ref.size())));
			else {
				int baseline = CodeTokens.distance(initial, ref);
				best = Math.max(best, baseline == 0 ? 0 : (double) (baseline - d) / baseline);
			}
		}
		return Math.clamp(best, 0, 1);
	}
	public static String normalize(String output) {
		String value = Objects.toString(output, "").replace("\r\n", "\n").replace('\r', '\n');
		return value.replaceAll("[\\t ]+(?=\\n|$)", "").replaceAll("\\n+$", "");
	}
	public static BigDecimal score20(BigDecimal score100) { return score100.divide(BigDecimal.valueOf(5), 2, RoundingMode.HALF_UP); }
}
