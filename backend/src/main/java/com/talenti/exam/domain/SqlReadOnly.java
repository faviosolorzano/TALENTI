package com.talenti.exam.domain;

import java.util.List;
import java.util.Set;

/** Defensa previa a la transacción de solo lectura y al rol SQL sin permisos de escritura. */
public final class SqlReadOnly {
	private SqlReadOnly() { }
	private static final Set<String> MUTATING = Set.of("insert", "update", "delete", "merge", "drop", "alter", "create",
			"copy", "grant", "revoke", "truncate", "call", "do", "execute", "set", "reset", "into", "vacuum");
	public static boolean accepts(String code) {
		List<String> tokens = CodeTokens.raw(code, ExamQuestion.Language.SQL);
		if (tokens.isEmpty() || (!tokens.getFirst().equals("select") && !tokens.getFirst().equals("with"))) return false;
		for (int i = 0; i < tokens.size(); i++) {
			String token = tokens.get(i);
			if (MUTATING.contains(token) || (token.equals(";") && i != tokens.size() - 1)) return false;
		}
		return true;
	}
	public static boolean hasOrderBy(String code) {
		List<String> tokens = CodeTokens.raw(code, ExamQuestion.Language.SQL);
		int depth = 0;
		for (int i = 0; i < tokens.size(); i++) {
			String token = tokens.get(i);
			if (token.equals("(")) depth++;
			else if (token.equals(")")) depth--;
			else if (depth == 0 && token.equals("order") && i + 1 < tokens.size() && tokens.get(i + 1).equals("by")) return true;
		}
		return false;
	}
}
