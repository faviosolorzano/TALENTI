package com.talenti.cv.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Texto sin mayúsculas ni tildes, para comparar (la misma idea que usa el catálogo de habilidades). */
public final class Fold {

	private static final Pattern MARKS = Pattern.compile("\\p{M}+");
	private static final Pattern SPACES = Pattern.compile("\\s+");

	private Fold() {
	}

	/** Minúsculas, sin tildes y con los espacios repetidos reducidos a uno. */
	public static String of(String text) {
		if (text == null) {
			return "";
		}
		String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
		String plain = MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT).strip();
		return SPACES.matcher(plain).replaceAll(" ");
	}

	/** Igual que {@link #of} pero sin recortar ni reducir espacios: conserva las posiciones de cada carácter. */
	public static String keepingPositions(String text) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			String decomposed = Normalizer.normalize(String.valueOf(text.charAt(i)), Normalizer.Form.NFD);
			String piece = MARKS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
			out.append(piece.length() == 1 ? piece : String.valueOf(text.charAt(i)).toLowerCase(Locale.ROOT).substring(0, 1));
		}
		return out.toString();
	}

}
