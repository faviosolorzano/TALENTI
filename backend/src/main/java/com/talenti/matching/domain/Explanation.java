package com.talenti.matching.domain;

/**
 * Explicación en español del resultado (007-13). {@code source} es {@code TEMPLATE} mientras la arme una plantilla
 * y no la IA (T1): solo entonces se muestra como "calculada con reglas" y sin el rótulo de IA.
 */
public record Explanation(String source, String version, String text) {

	public static final String TEMPLATE = "TEMPLATE";

}
