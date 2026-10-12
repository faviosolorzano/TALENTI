package com.talenti.vacancy.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Texto de la búsqueda (004-06, 004-26, 004-27): se limpia, se parte en palabras útiles y cada una se convierte en
 * una expresión regular de palabra completa o inicio de palabra. El texto buscado nunca se guarda ni se escribe en
 * los registros (principio 5).
 */
public final class SearchText {

	/** Largo máximo de {@code q} (004-10); se mide sobre lo que escribió la persona, relleno incluido. */
	public static final int MAX_LENGTH = 80;

	/**
	 * Máximo de palabras útiles de {@code q} (004-10): más de 6 se rechazan con 422. Se cuentan <b>después</b> de
	 * descartar el relleno, repetidas y letras sueltas: una frase natural ("quiero un practicante de java con spring
	 * boot") no se rechaza por sus palabras de relleno, y lo que importa limitar es el trabajo de la consulta, que
	 * crece con las palabras útiles. El largo total sigue acotado por {@link #MAX_LENGTH}.
	 */
	public static final int MAX_TERMS = 6;

	/** Desde este largo (en letras, ya normalizado) una palabra también coincide como inicio de palabra. */
	public static final int PREFIX_MIN_LENGTH = 5;

	/** Desde este largo una palabra también coincide con palabras parecidas, para errores de tipeo (004-31). */
	public static final int FUZZY_MIN_LENGTH = 4;

	/**
	 * Parecido mínimo ({@code similarity()} de pg_trgm, de 0 a 1) entre la palabra buscada y una palabra entera del
	 * texto. Calibrado con pruebas (VacancySearchRelevanceTests, 004-31) sobre los trigramas reales:
	 * "pyton"/"python" 0,44, "bakend"/"backend" 0,50, "javva"/"java" 0,57 y "reac"/"react" 0,57 pasan, mientras que
	 * "java"/"javascript" da 0,33 y no. 0,4 deja margen a ambos lados; subirlo a 0,45 perdería "pyton".
	 */
	public static final float SIMILARITY_THRESHOLD = 0.4f;

	private static final String NO_ALNUM_BEFORE = "(?<![[:alnum:]])";

	private static final String NO_ALNUM_AFTER = "(?![[:alnum:]])";

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
	 * Las palabras útiles del texto ya limpio, sin repetir (sin distinguir mayúsculas ni tildes) y en su orden.
	 * Se descartan: la puntuación que rodea a una palabra ("java," "¿react?" "node."), las de una sola letra o cifra
	 * ("a", "5": coinciden con casi todo) y las de relleno de {@link SearchStopWords} (004-26). Un símbolo suelto
	 * ("%", "_") sí cuenta, porque se busca como texto. Si no queda ninguna, la búsqueda no filtra.
	 */
	public static List<String> terms(String cleaned) {
		if (cleaned == null || cleaned.isBlank()) {
			return List.of();
		}
		Map<String, String> unique = new LinkedHashMap<>();
		for (String written : cleaned.split(" ")) {
			String term = stripEdges(written);
			String normalized = TextNormalizer.normalize(term);
			// Una marca de acento suelta se normaliza a nada: no hay nada que buscar.
			if (!normalized.isEmpty() && !isSingleLetterOrDigit(normalized) && !SearchStopWords.isStopWord(term)) {
				unique.putIfAbsent(normalized, term);
			}
		}
		return new ArrayList<>(unique.values());
	}

	/**
	 * Quita la puntuación de frase al inicio y al final de una palabra. Los puntos solo se quitan al final: ".NET"
	 * empieza con punto, pero "node." termina una oración. {@code # + / % _ \ ' "} no se tocan (son parte de
	 * "c#", "c++", "100%", "O'Reilly" y se buscan como texto).
	 */
	private static String stripEdges(String word) {
		int start = 0;
		int end = word.length();
		while (start < end && ",;:!?¿¡()".indexOf(word.charAt(start)) >= 0) {
			start++;
		}
		while (end > start && ",;:!?¿¡().".indexOf(word.charAt(end - 1)) >= 0) {
			end--;
		}
		return word.substring(start, end);
	}

	private static boolean isSingleLetterOrDigit(String term) {
		return term.codePointCount(0, term.length()) == 1 && Character.isLetterOrDigit(term.codePointAt(0));
	}

	/**
	 * Expresión regular de PostgreSQL ({@code ~}) para buscar el término en un texto ya pasado por
	 * {@code lower(unaccent(...))} (004-27):
	 * <ul>
	 * <li>El término se normaliza (minúsculas, sin tildes) y se escapa por completo: ningún carácter del visitante
	 * actúa como metacarácter (004-06). Va siempre como parámetro, nunca unido a la consulta.</li>
	 * <li>Coincide por <b>palabra completa</b>: no puede haber una letra o cifra pegada antes ni después, así que
	 * "java" no coincide dentro de "JavaScript". Si tiene {@value #PREFIX_MIN_LENGTH} letras o más, también por
	 * <b>inicio de palabra</b> (solo se exige el borde de antes): "programacion" coincide con "programación".</li>
	 * <li>El borde se exige solo donde el término empieza o termina en letra o cifra. Así "c#", "node.js" y
	 * "100%" funcionan; no se usan {@code \m} ni {@code \M} porque fallan con símbolos.</li>
	 * </ul>
	 *
	 * @param term una palabra que no queda vacía al normalizarla; {@link #terms(String)} ya descarta las que sí
	 * @throws IllegalArgumentException si la palabra es nula, está vacía o solo tiene marcas de acento
	 */
	public static String matchPattern(String term) {
		String normalized = TextNormalizer.normalize(term);
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("La palabra buscada no puede quedar vacía al normalizarla");
		}
		StringBuilder pattern = new StringBuilder();
		if (isAlnum(normalized.codePointAt(0))) {
			pattern.append(NO_ALNUM_BEFORE);
		}
		pattern.append(escapeRegex(normalized));
		boolean prefixAllowed = normalized.codePointCount(0, normalized.length()) >= PREFIX_MIN_LENGTH;
		if (!prefixAllowed && isAlnum(normalized.codePointBefore(normalized.length()))) {
			pattern.append(NO_ALNUM_AFTER);
		}
		return pattern.toString();
	}

	/**
	 * La palabra normalizada para buscar por parecido (004-31), o vacío si no corresponde: solo las de
	 * {@value #FUZZY_MIN_LENGTH} letras o más y hechas únicamente de letras y cifras ("c#", "node.js" y "100%" se
	 * buscan solo de forma exacta). Se compara con cada palabra entera del texto mediante {@code similarity()} de
	 * pg_trgm. Una palabra nula, vacía o sin letras al normalizarla no es elegible (devuelve vacío).
	 */
	public static Optional<String> fuzzyWord(String term) {
		String normalized = TextNormalizer.normalize(term);
		boolean eligible = !normalized.isEmpty() && normalized.codePointCount(0, normalized.length()) >= FUZZY_MIN_LENGTH
				&& normalized.codePoints().allMatch(SearchText::isAlnum);
		return eligible ? Optional.of(normalized) : Optional.empty();
	}

	private static boolean isAlnum(int codePoint) {
		return Character.isLetterOrDigit(codePoint);
	}

	/** Antepone una barra invertida a cada metacarácter de las expresiones regulares avanzadas de PostgreSQL. */
	private static String escapeRegex(String text) {
		StringBuilder escaped = new StringBuilder(text.length() + 8);
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
				escaped.append('\\');
			}
			escaped.append(c);
		}
		return escaped.toString();
	}

}
