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
	public record SqlColumn(String name, String type) { }
	public record SqlFixture(String name, List<SqlColumn> columns, List<List<String>> rows) {
		public SqlFixture {
			columns = List.copyOf(columns);
			rows = rows.stream().map(row -> java.util.Collections.unmodifiableList(new java.util.ArrayList<>(row))).toList();
		}
	}
	public record Case(String id, boolean visible, String input, String expectedOutput, String setupSql,
			SqlTable expectedTable, List<SqlFixture> tables) {
		public Case { tables = tables == null ? List.of() : List.copyOf(tables); }
		public Case(String id, boolean visible, String input, String expectedOutput, String setupSql, SqlTable expectedTable) {
			this(id,visible,input,expectedOutput,setupSql,expectedTable,List.of());
		}
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
	public ExamQuestion withId(UUID value) { return new ExamQuestion(value,slot,type,skillSlug,difficulty,points,prompt,
			options,correctId,rubric,keyPoints,language,kind,initialCode,references,cases,orderMatters,source); }
	/** Ningún id visible debe revelar la clave por nombres como 'correct' o 'answer'. */
	public ExamQuestion opaqueChoices() {
		if(type!=Type.CHOICE||options.stream().anyMatch(o->o==null||o.id()==null)) return this;
		Map<String,String> ids=new java.util.HashMap<>();
		options.forEach(o->ids.computeIfAbsent(o.id(),key->UUID.randomUUID().toString()));
		return new ExamQuestion(id,slot,type,skillSlug,difficulty,points,prompt,
				options.stream().map(o->new Option(ids.get(o.id()),o.text())).toList(),ids.getOrDefault(correctId,correctId),
				rubric,keyPoints,language,kind,initialCode,references,cases,orderMatters,source);
	}
}
