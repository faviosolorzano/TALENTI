package com.talenti.vacancy.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Texto de la búsqueda (004-06): se limpia, se parte en términos y cada término se convierte en un patrón de
 * {@code ILIKE}. El texto buscado nunca se guarda ni se escribe en los registros (principio 5).
 */
public final class SearchText {

	/** Largo máximo de {@code q} (004-10). */
	public static final int MAX_LENGTH = 80;

	/** Máximo de términos de {@code q} que cuentan (004-10): más de 6 se rechazan con 422. */
	public static final int MAX_TERMS = 6;

	private SearchText() {
	}

	/**
	 * Recorta, convierte los caracteres de control (incluido NUL, que PostgreSQL rechaza) en espacios y colapsa los
	 * espacios. Devuelve texto vacío si no queda nada que buscar.
	 */
	public static String clean(String raw) {
		if (raw == null) {
			return "";
		}
		return raw.replaceAll("[\\p{Cc}\\p{Z}]+", " ").strip();
	}

	/**
	 * Los términos del texto ya limpio, sin repetir (sin distinguir mayúsculas ni tildes) y en su orden. Se ignoran
	 * los de una sola letra o cifra ("a", "5"): coinciden con casi todo y no ayudan a buscar. Un símbolo suelto
	 * ("%", "_") sí cuenta, porque se busca como texto.
	 */
	public static List<String> terms(String cleaned) {
		if (cleaned == null || cleaned.isBlank()) {
			return List.of();
		}
		Map<String, String> unique = new LinkedHashMap<>();
		for (String term : cleaned.split(" ")) {
			if (!term.isEmpty() && !isSingleLetterOrDigit(term)) {
				unique.putIfAbsent(TextNormalizer.normalize(term), term);
			}
		}
		return new ArrayList<>(unique.values());
	}

	private static boolean isSingleLetterOrDigit(String term) {
		return term.codePointCount(0, term.length()) == 1 && Character.isLetterOrDigit(term.codePointAt(0));
	}

	/**
	 * Patrón {@code ILIKE} para "contiene el término": {@code %}, {@code _} y la barra invertida se escapan para
	 * que se traten como texto (004-06). Las comillas no necesitan nada: el valor siempre viaja como parámetro.
	 */
	public static String containsPattern(String term) {
		String escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
		return "%" + escaped + "%";
	}

}
