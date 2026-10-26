package com.talenti.exam.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Documento interno de una pregunta. Nunca se serializa directamente en la API del estudiante. */
public record ExamQuestion(UUID id, int slot, Type type, String skillSlug, int difficulty, BigDecimal points,
		String prompt, List<Option> options, String correctId, List<Criterion> rubric, List<String> keyPoints,
		Language language, CodeKind kind, String initialCode, List<String> references, List<Case> cases,
		boolean orderMatters, Source source) {

	public enum Type { CHOICE, OPEN, CODE }
	public enum Language { JAVA, JAVASCRIPT, PYTHON, SQL }
	public enum CodeKind { WRITE, FIX, COMPLETE }
	public enum Source { AI, BANK }
	public record Option(String id, String text) { }
	public record Criterion(String id, String description, Map<Integer, String> levels) {
		public Criterion { levels = Map.copyOf(levels); }
	}
	public record Case(String id, boolean visible, String input, String expectedOutput, String setupSql,
			SqlTable expectedTable) {
		@Override public String toString() { return "Case[id=" + id + ", visible=" + visible + "]"; }
	}
	public record SqlTable(List<String> columns, List<List<String>> rows, List<String> types) {
		public SqlTable {
			columns = List.copyOf(columns);
			rows = rows.stream().map(row -> java.util.Collections.unmodifiableList(new java.util.ArrayList<>(row))).toList();
			types = types == null ? java.util.Collections.nCopies(columns.size(), "text") : List.copyOf(types);
			int width = columns.size();
			if (types.size() != width || rows.stream().anyMatch(row -> row.size() != width))
				throw new IllegalArgumentException("Tabla SQL inconsistente");
		}
		public SqlTable(List<String> columns, List<List<String>> rows) { this(columns, rows, null); }
		@Override public String toString() { return "SqlTable[columns=" + columns.size() + ", rows=" + rows.size() + "]"; }
	}
	public ExamQuestion {
		options = options == null ? List.of() : List.copyOf(options);
		rubric = rubric == null ? List.of() : List.copyOf(rubric);
		keyPoints = keyPoints == null ? List.of() : List.copyOf(keyPoints);
		references = references == null ? List.of() : List.copyOf(references);
		cases = cases == null ? List.of() : List.copyOf(cases);
	}
	@Override public String toString() { return "ExamQuestion[id=" + id + ", slot=" + slot + ", type=" + type + "]"; }
}
