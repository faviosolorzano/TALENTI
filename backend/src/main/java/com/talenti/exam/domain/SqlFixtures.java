package com.talenti.exam.domain;

import java.util.HashSet;
import java.util.Set;
import com.talenti.exam.domain.ExamQuestion.SqlFixture;

/** El montaje SQL es estructurado: jamás se ejecuta DDL arbitrario enviado por la IA. */
public final class SqlFixtures {
	private SqlFixtures() { }
	public static final Set<String> TYPES = Set.of("integer","bigint","numeric","text","boolean","date","timestamptz");
	public static boolean identifier(String name) { return name != null && name.matches("[a-z][a-z0-9_]{0,39}") && !name.startsWith("pg_"); }
	public static boolean valid(java.util.List<SqlFixture> fixtures) {
		if (fixtures == null || fixtures.isEmpty() || fixtures.size() > 6) return false;
		Set<String> tables = new HashSet<>();
		int rows = 0;
		for (SqlFixture table : fixtures) {
			if (table == null || !identifier(table.name()) || !tables.add(table.name()) || table.columns().isEmpty() || table.columns().size() > 20) return false;
			Set<String> columns = new HashSet<>();
			for (var column : table.columns()) if (column == null || !identifier(column.name()) || !columns.add(column.name()) || !TYPES.contains(column.type())) return false;
			rows += table.rows().size();
			if (rows > 1000 || table.rows().stream().anyMatch(row -> row.size() != table.columns().size()
					|| row.stream().anyMatch(value -> value != null && value.length() > 2000))) return false;
		}
		return true;
	}
}
