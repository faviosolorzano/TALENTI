package com.talenti.practice.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * Corta en frases el texto que llega en fragmentos desde el modelo (012-09): cada frase completa sale en cuanto termina,
 * para que la voz empiece antes de que el modelo acabe. Una frase termina en {@code .}, {@code !}, {@code ?} o
 * {@code …} seguido de un espacio o de un salto de línea (así "3.5" no se corta). Código puro, sin estado compartido:
 * una instancia por turno.
 */
public final class SentenceSplitter {

	private final StringBuilder pending = new StringBuilder();

	/** Agrega un fragmento y devuelve las frases que quedaron completas (puede ser ninguna). */
	public List<String> add(String fragment) {
		List<String> sentences = new ArrayList<>();
		if (fragment == null || fragment.isEmpty()) {
			return sentences;
		}
		pending.append(fragment);
		int start = 0;
		for (int i = 0; i < pending.length() - 1; i++) {
			if (isTerminator(pending.charAt(i)) && Character.isWhitespace(pending.charAt(i + 1))) {
				int end = i + 1;
				while (end < pending.length() - 1 && isTerminator(pending.charAt(end))) {
					end++;
				}
				String sentence = pending.substring(start, end).strip();
				if (!sentence.isEmpty()) {
					sentences.add(sentence);
				}
				start = end;
			}
		}
		pending.delete(0, start);
		return sentences;
	}

	/** Lo que queda al terminar el modelo (una última frase sin punto final, si la hay). */
	public String flush() {
		String rest = pending.toString().strip();
		pending.setLength(0);
		return rest;
	}

	private static boolean isTerminator(char c) {
		return c == '.' || c == '!' || c == '?' || c == '…';
	}

}
