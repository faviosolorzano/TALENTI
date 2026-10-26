package com.talenti.exam.domain;

import java.nio.charset.StandardCharsets;
import java.util.*;
import com.talenti.exam.domain.ExamQuestion.*;
import com.talenti.exam.port.CodeExecutionPort;
import com.talenti.exam.port.CodeExecutionPort.Result;

/** No deja READY una versión por la sola afirmación de la IA: ejecuta todas sus referencias dos veces. */
public final class ExamVerifier {
	public static final String VERSION = "exam-verifier-v1";
	public record Report(boolean accepted, Map<Integer, String> failures, String verifierVersion) { }
	private final CodeExecutionPort execution;
	private final CodeExecutionPort.Limits limits;
	private final int visibleCases, hiddenCases;
	public ExamVerifier(CodeExecutionPort execution, CodeExecutionPort.Limits limits, int visibleCases, int hiddenCases) {
		this.execution = execution; this.limits = limits; this.visibleCases = visibleCases; this.hiddenCases = hiddenCases;
	}
	public Report verify(Blueprint blueprint, List<ExamQuestion> questions) {
		Map<Integer, String> failures = new LinkedHashMap<>();
		if (questions == null || questions.size() != 10 || blueprint.slots().size() != 10)
			return new Report(false, Map.of(0, "QUESTION_COUNT"), VERSION);
		Set<UUID> ids = new HashSet<>(); Set<Integer> slots = new HashSet<>();
		for (ExamQuestion q : questions) {
			if (q == null || q.slot() < 1 || q.slot() > 10) { failures.put(0, "INVALID_SLOT"); continue; }
			var slot = blueprint.slots().get(q.slot() - 1);
			String reason = !slots.add(q.slot()) || q.id() == null || !ids.add(q.id()) ? "DUPLICATE_QUESTION"
					: !Objects.equals(q.type(), slot.type()) || !Objects.equals(q.skillSlug(), slot.skillSlug())
					|| q.difficulty() != slot.difficulty() || q.points() == null || q.points().compareTo(slot.points()) != 0
					|| q.language() != slot.language() || q.kind() != slot.kind() ? "BLUEPRINT_MISMATCH" : validate(q);
			if (reason != null) failures.put(q.slot(), reason);
		}
		return new Report(failures.isEmpty(), Map.copyOf(failures), VERSION);
	}
	private String validate(ExamQuestion q) {
		if (!text(q.prompt(), 2000) || ExamSafety.forbidden(q.prompt()) || q.source() == null) return "PROMPT";
		return switch (q.type()) {
			case CHOICE -> choice(q);
			case OPEN -> open(q);
			case CODE -> code(q);
		};
	}
	private String choice(ExamQuestion q) {
		if (q.options().size() != 4) return "OPTIONS";
		Set<String> ids = new HashSet<>(), texts = new HashSet<>();
		for (Option o : q.options()) {
			if (o == null || !text(o.id(), 60) || !text(o.text(), 500) || !ids.add(o.id())
					|| !texts.add(o.text().strip().toLowerCase(Locale.ROOT)) || ExamSafety.forbidden(o.text())
					|| o.text().toLowerCase(Locale.ROOT).matches(".*(todas|ninguna|all|none).*(anteriores|above).*")) return "OPTIONS";
		}
		return ids.contains(q.correctId()) ? null : "CORRECT_OPTION";
	}
	private String open(ExamQuestion q) {
		if (q.rubric().size() < 3 || q.rubric().size() > 4 || q.keyPoints().isEmpty() || q.keyPoints().size() > 10) return "RUBRIC";
		Set<String> ids = new HashSet<>();
		for (Criterion c : q.rubric()) {
			if (c == null || !text(c.id(), 60) || !ids.add(c.id()) || !text(c.description(), 500)
					|| ExamSafety.personalComment(c.description()) || !c.levels().keySet().equals(Set.of(1, 2, 3, 4))
					|| c.levels().values().stream().anyMatch(s -> !text(s, 500) || ExamSafety.personalComment(s))) return "RUBRIC";
		}
		return q.keyPoints().stream().anyMatch(s -> !text(s, 500) || ExamSafety.forbidden(s)) ? "KEY_POINTS" : null;
	}
	private String code(ExamQuestion q) {
		if (q.language() == null || q.kind() == null || q.references().isEmpty() || q.references().size() > 4
				|| q.references().stream().anyMatch(s -> !codeText(s)) || (q.initialCode() != null && !q.initialCode().isEmpty() && !codeText(q.initialCode()))) return "CODE";
		if (q.cases().size() != visibleCases + hiddenCases || q.cases().stream().filter(Case::visible).count() != visibleCases
				|| q.cases().stream().anyMatch(c -> c.id() == null || c.input() != null && c.input().length() > 10000
				|| c.expectedOutput() != null && c.expectedOutput().length() > limits.outputBytes()
				|| c.setupSql() != null && c.setupSql().length() > 20000)
				|| q.cases().stream().map(Case::id).distinct().count() != q.cases().size()) return "CASES";
		for (String reference : q.references()) {
			if (q.language() == Language.SQL && (!SqlReadOnly.accepts(reference) || q.orderMatters() && !SqlReadOnly.hasOrderBy(reference))) return "SQL_REFERENCE";
			List<Result> first = execution.run(q.language(), reference, q.cases(), limits);
			List<Result> second = execution.run(q.language(), reference, q.cases(), limits);
			if (first.size() != q.cases().size() || second.size() != first.size()) return "REFERENCE_EXECUTION";
			for (int i = 0; i < first.size(); i++) {
				Result a = first.get(i), b = second.get(i); Case c = q.cases().get(i);
				if (!a.completed() || !b.completed()) return "REFERENCE_EXECUTION";
				if (q.language() == Language.SQL) {
					if (a.table() == null || b.table() == null || c.expectedTable() == null || a.table().rows().isEmpty()
							|| a.table().rows().size() > 200 || SqlSimilarity.compare(a.table(), b.table(), q.orderMatters(), .2) < 1
							|| SqlSimilarity.compare(c.expectedTable(), a.table(), q.orderMatters(), .2) < 1) return "SQL_RESULTS";
				}
				else if (!ExamScoring.normalize(a.output()).equals(ExamScoring.normalize(b.output()))
						|| !ExamScoring.normalize(a.output()).equals(ExamScoring.normalize(c.expectedOutput()))) return "REFERENCE_RESULTS";
			}
		}
		if (q.kind() != CodeKind.WRITE) {
			if (!codeText(q.initialCode())) return "INITIAL_CODE";
			List<Result> initial = execution.run(q.language(), q.initialCode(), q.cases(), limits);
			boolean allPassed = initial.size() == q.cases().size();
			for (int i = 0; i < initial.size() && allPassed; i++) {
				Result r = initial.get(i); Case c = q.cases().get(i);
				allPassed = r.completed() && (q.language() == Language.SQL ? r.table() != null && c.expectedTable() != null
						&& SqlSimilarity.compare(c.expectedTable(), r.table(), q.orderMatters(), .2) >= 1
						: ExamScoring.normalize(r.output()).equals(ExamScoring.normalize(c.expectedOutput())));
			}
			if (allPassed) return "INITIAL_ALREADY_CORRECT";
		}
		return null;
	}
	private boolean codeText(String text) { return text != null && !text.isBlank() && text.getBytes(StandardCharsets.UTF_8).length <= limits.codeBytes(); }
	private static boolean text(String text, int max) { return text != null && !text.isBlank() && text.length() <= max; }
}
