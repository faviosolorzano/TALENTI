package com.talenti.practice.domain;

import java.text.Normalizer;
import java.util.Locale;

/** Texto en minúsculas, sin tildes y con espacios simples: para comparar y filtrar sin depender de la escritura. */
final class Folding {

	private Folding() {
	}

	static String fold(String text) {
		if (text == null) {
			return "";
		}
		String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return decomposed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
	}

}
