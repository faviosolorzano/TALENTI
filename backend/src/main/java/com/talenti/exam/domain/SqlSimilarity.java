package com.talenti.exam.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

/** Columnas por nombre y filas como multiconjuntos; con orden, subsecuencia común más larga. */
public final class SqlSimilarity {
	private SqlSimilarity() { }
	public static double compare(ExamQuestion.SqlTable expected, ExamQuestion.SqlTable actual,
			boolean ordered, double columnsWeight) {
		List<String> eCols = expected.columns().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
		List<String> aCols = actual.columns().stream().map(s -> s.toLowerCase(Locale.ROOT)).toList();
		var eCounts = counts(eCols); var aCounts = counts(aCols);
		int common = eCounts.entrySet().stream().mapToInt(e -> Math.min(e.getValue(), aCounts.getOrDefault(e.getKey(), 0))).sum();
		int max = Math.max(eCols.size(), aCols.size());
		double columns = max == 0 ? 1 : (double) common / max;
		var union = new TreeSet<String>(); union.addAll(eCols); union.addAll(aCols);
		List<List<String>> eRows = rows(expected, eCols, union), aRows = rows(actual, aCols, union);
		int total = eRows.size() + aRows.size();
		int intersection;
		if (ordered) intersection = lcs(eRows, aRows);
		else {
			var ec = counts(eRows); var ac = counts(aRows);
			intersection = ec.entrySet().stream().mapToInt(e -> Math.min(e.getValue(), ac.getOrDefault(e.getKey(), 0))).sum();
		}
		double rowScore = total == 0 ? 1 : 2.0 * intersection / total;
		return columnsWeight * columns + (1 - columnsWeight) * rowScore;
	}
	private static <T> Map<T, Integer> counts(List<T> values) {
		Map<T, Integer> result = new HashMap<>(); values.forEach(v -> result.merge(v, 1, Integer::sum)); return result;
	}
	private static List<List<String>> rows(ExamQuestion.SqlTable table, List<String> columns, Set<String> union) {
		return table.rows().stream().map(row -> {
			List<String> normalized = new ArrayList<>();
			for (String column : union) {
				boolean found = false;
				for (int i = 0; i < columns.size(); i++) {
					if (columns.get(i).equals(column)) { normalized.add(normalize(row.get(i), table.types().get(i))); found = true; }
				}
				if (!found) normalized.add("MISSING");
			}
			return List.copyOf(normalized);
		}).toList();
	}
	public static String normalize(String value, String type) {
		if (value == null) return "NULL";
		String stripped = value.strip();
		String sqlType = type.toLowerCase(Locale.ROOT);
		if (Set.of("int2", "int4", "int8", "smallint", "integer", "bigint", "float4", "float8", "real", "double precision", "numeric", "decimal").contains(sqlType)) {
			try { return "N:" + new BigDecimal(stripped).setScale(2, RoundingMode.HALF_UP).toPlainString(); }
			catch (NumberFormatException ignored) { return "N:" + stripped; }
		}
		if (sqlType.contains("timestamp")) {
			try { return "D:" + OffsetDateTime.parse(stripped).toInstant().toString(); }
			catch (java.time.format.DateTimeParseException ignored) { }
			try { return "D:" + Instant.parse(stripped).toString(); }
			catch (java.time.format.DateTimeParseException ignored) { }
		}
		if (sqlType.equals("date")) return "DATE:" + stripped;
		return "S:" + stripped;
	}
	private static int lcs(List<List<String>> a, List<List<String>> b) {
		int[] previous = new int[b.size() + 1];
		for (var row : a) {
			int[] current = new int[b.size() + 1];
			for (int j = 1; j <= b.size(); j++) current[j] = row.equals(b.get(j - 1))
					? previous[j - 1] + 1 : Math.max(previous[j], current[j - 1]);
			previous = current;
		}
		return previous[b.size()];
	}
}
