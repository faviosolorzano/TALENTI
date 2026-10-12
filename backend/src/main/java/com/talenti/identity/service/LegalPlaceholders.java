package com.talenti.identity.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detecta marcadores sin completar en un texto legal, como {@code [titular del servicio]} o
 * {@code [correo de contacto]}. No cuenta los enlaces de Markdown ({@code [texto](/ruta)}). Función pura.
 */
final class LegalPlaceholders {

	/** Corchetes con texto adentro que no van seguidos de "(" (eso sería un enlace). */
	private static final Pattern PLACEHOLDER = Pattern.compile("\\[([^\\[\\]\\n]+)](?!\\()");

	private LegalPlaceholders() {
	}

	/** Los marcadores distintos, con sus corchetes, en el orden en que aparecen. */
	static List<String> find(String markdown) {
		Set<String> found = new LinkedHashSet<>();
		Matcher matcher = PLACEHOLDER.matcher(markdown);
		while (matcher.find()) {
			found.add(matcher.group());
		}
		return List.copyOf(found);
	}

}
