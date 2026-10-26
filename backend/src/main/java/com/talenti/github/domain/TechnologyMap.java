package com.talenti.github.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Mapa versionado de nombres de GitHub (lenguajes y temas) a {@code slug} del catálogo (013-10, principio 11). Lo que
 * no está aquí se busca por los sinónimos del catálogo. Formato del recurso: una línea {@code nombre=slug}; las que
 * empiezan con {@code #} son comentarios, y {@code # version: X} fija la versión.
 */
public record TechnologyMap(String version, Map<String, String> slugByName) {

	public TechnologyMap {
		slugByName = Map.copyOf(slugByName);
	}

	public static TechnologyMap parse(List<String> lines) {
		String version = "sin-version";
		Map<String, String> entries = new LinkedHashMap<>();
		for (String raw : lines) {
			String line = raw.strip();
			if (line.isEmpty()) {
				continue;
			}
			if (line.startsWith("#")) {
				String comment = line.substring(1).strip();
				if (comment.toLowerCase(Locale.ROOT).startsWith("version:")) {
					version = comment.substring("version:".length()).strip();
				}
				continue;
			}
			int eq = line.lastIndexOf('=');
			if (eq <= 0 || eq == line.length() - 1) {
				throw new IllegalArgumentException("Línea mal formada en el mapa de tecnologías de GitHub");
			}
			entries.put(key(line.substring(0, eq)), line.substring(eq + 1).strip());
		}
		return new TechnologyMap(version, entries);
	}

	public Optional<String> slugFor(String githubName) {
		return Optional.ofNullable(slugByName.get(key(githubName)));
	}

	private static String key(String name) {
		return name.strip().toLowerCase(Locale.ROOT);
	}

}
