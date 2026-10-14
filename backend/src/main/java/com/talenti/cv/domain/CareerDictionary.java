package com.talenti.cv.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Diccionario configurable de carreras (009-12). Cada línea: {@code Nombre que se sugiere|sinónimo|sinónimo}. Busca la
 * frase más larga que aparezca en una línea (así "Diseño y Desarrollo de Software" gana a "Desarrollo de Software").
 */
public final class CareerDictionary {

	private record Entry(String canonical, String folded, Pattern pattern) {
	}

	private final List<Entry> entries = new ArrayList<>();

	public CareerDictionary(List<String> lines) {
		for (String line : lines) {
			String trimmed = line.strip();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				continue;
			}
			String[] parts = trimmed.split("\\|");
			String canonical = parts[0].strip();
			for (String part : parts) {
				String folded = Fold.of(part);
				if (folded.length() >= 4) {
					entries.add(new Entry(canonical, folded, Pattern
							.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(folded) + "(?![\\p{L}\\p{N}])")));
				}
			}
		}
		entries.sort(Comparator.comparingInt((Entry entry) -> entry.folded().length()).reversed());
	}

	/** El nombre de la carrera cuya frase (la más larga) aparece en la línea. */
	public Optional<String> find(String line) {
		String folded = Fold.of(line);
		for (Entry entry : entries) {
			if (entry.pattern().matcher(folded).find()) {
				return Optional.of(entry.canonical());
			}
		}
		return Optional.empty();
	}

}
