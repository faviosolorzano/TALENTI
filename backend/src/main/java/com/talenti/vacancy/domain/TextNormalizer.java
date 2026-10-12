package com.talenti.vacancy.domain;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Normaliza texto para compararlo sin distinguir mayúsculas, tildes ni espacios sobrantes. Es la misma idea que
 * {@code lower(unaccent(...))} de la base de datos, para las comparaciones que se hacen en Java (sinónimos del
 * catálogo y duplicados de términos).
 */
public final class TextNormalizer {

	private TextNormalizer() {
	}

	public static String normalize(String text) {
		if (text == null) {
			return "";
		}
		String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD);
		return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
	}

}
