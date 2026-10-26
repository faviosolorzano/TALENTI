package com.talenti.exam.domain;

import java.util.*;
import com.talenti.exam.domain.ExamQuestion.Language;

/** Tokenizador acotado por el tamaño de código de la spec; conserva literales, palabras clave y operadores. */
public final class CodeTokens {
	private CodeTokens() { }
	private static final Set<String> KEYWORDS = Set.of(("abstract assert boolean break byte case catch char class const continue "
			+ "default do double else enum extends final finally float for if implements import instanceof int interface long "
			+ "native new package private protected public return short static strictfp super switch synchronized this throw throws "
			+ "transient try void volatile while var let function async await yield export from of in null true false undefined "
			+ "def lambda elif except raise with as and or not is none pass print input range len str list dict set tuple "
			+ "select distinct where join left right inner outer on group by having order asc desc limit offset union all "
			+ "exists like ilike between insert update delete create drop alter table values into nulls first last count sum avg min max "
			+ "coalesce cast over partition recursive then end when int4 int8 numeric varchar text date timestamp").split(" "));
	public static List<String> raw(String code, Language language) {
		List<String> result = new ArrayList<>();
		String value = code == null ? "" : code;
		for (int i = 0; i < value.length();) {
			char c = value.charAt(i);
			if (Character.isWhitespace(c)) { i++; continue; }
			if ((language == Language.PYTHON && c == '#')
					|| (language == Language.SQL && value.startsWith("--", i))
					|| ((language == Language.JAVA || language == Language.JAVASCRIPT) && value.startsWith("//", i))) {
				while (i < value.length() && value.charAt(i) != '\n') i++; continue;
			}
			if (language != Language.PYTHON && value.startsWith("/*", i)) {
				int depth = 1; i += 2;
				while (i < value.length() && depth > 0) {
					if (value.startsWith("/*", i)) { depth++; i += 2; }
					else if (value.startsWith("*/", i)) { depth--; i += 2; }
					else i++;
				}
				continue;
			}
			int start = i++;
			if (c == '\'' || c == '"' || c == '`') {
				boolean triple = language == Language.PYTHON && value.startsWith(String.valueOf(c).repeat(3), start);
				if (triple) i = start + 3;
				while (i < value.length()) {
					if (value.charAt(i) == '\\' && language != Language.SQL) { i = Math.min(i + 2, value.length()); continue; }
					if (triple && value.startsWith(String.valueOf(c).repeat(3), i)) { i += 3; break; }
					char current = value.charAt(i++);
					if (!triple && current == c) {
						if (language == Language.SQL && i < value.length() && value.charAt(i) == c) { i++; continue; }
						break;
					}
				}
			}
			else if (Character.isJavaIdentifierStart(c)) {
				while (i < value.length() && Character.isJavaIdentifierPart(value.charAt(i))) i++;
			}
			else if (Character.isDigit(c)) {
				while (i < value.length() && (Character.isLetterOrDigit(value.charAt(i)) || value.charAt(i) == '.')) i++;
			}
			else if (i < value.length() && Set.of("==", "!=", "<=", ">=", "++", "--", "&&", "||", "+=", "-=", "*=", "/=", "=>", "::", "**", "//", "<<", ">>").contains(value.substring(start, i + 1))) i++;
			String token = value.substring(start, i);
			result.add(language == Language.SQL && identifier(token) ? token.toLowerCase(Locale.ROOT) : token);
		}
		return List.copyOf(result);
	}
	public static List<String> normalized(String code, Language language, Set<String> known) {
		return raw(code, language).stream().map(token -> identifier(token)
				&& !KEYWORDS.contains(token.toLowerCase(Locale.ROOT)) && !known.contains(token) ? "ID" : token).toList();
	}
	private static boolean identifier(String token) { return !token.isEmpty() && Character.isJavaIdentifierStart(token.charAt(0)); }
	public static int distance(List<String> a, List<String> b) {
		int[] previous = new int[b.size() + 1];
		for (int j = 0; j <= b.size(); j++) previous[j] = j;
		for (int i = 1; i <= a.size(); i++) {
			int[] current = new int[b.size() + 1]; current[0] = i;
			for (int j = 1; j <= b.size(); j++) current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
					previous[j - 1] + (a.get(i - 1).equals(b.get(j - 1)) ? 0 : 1));
			previous = current;
		}
		return previous[b.size()];
	}
}
