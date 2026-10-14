package com.talenti.cv.domain;

import java.text.Normalizer;
import java.util.Locale;

/** Texto sin mayúsculas ni tildes, para comparar (la misma idea que usa el catálogo de habilidades). */
public final class Fold {

	private Fold() {
	}

	/** Minúsculas, sin tildes y con los espacios repetidos reducidos a uno. */
	public static String of(String text) {
		if (text == null) {
			return "";
		}
		String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
		return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
	}

	/** Igual que {@link #of} pero sin recortar ni reducir espacios: conserva las posiciones de cada carácter. */
	public static String keepingPositions(String text) {
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			String piece = Normalizer.normalize(String.valueOf(text.charAt(i)), Normalizer.Form.NFD)
					.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
			out.append(piece.length() == 1 ? piece : String.valueOf(text.charAt(i)).toLowerCase(Locale.ROOT).substring(0, 1));
		}
		return out.toString();
	}

}
