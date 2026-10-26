package com.talenti.exam.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** Filtro local de contenido protegido (no depende de otro módulo ni de una IA). */
public final class ExamSafety {
	private ExamSafety() { }
	private static final Pattern TOPICS = Pattern.compile("\\b(estado civil|embaraz[oa]|religio\\w*|orientacion sexual|"
			+ "partido politico|raza|etnia|discapacidad|salud mental|hijos|nacionalidad|edad|sexo|genero)\\b");
	private static final Pattern TRAITS = Pattern.compile("\\b(acento|entonacion|emocion\\w*|nervios\\w*|apariencia|"
			+ "personalidad|atractiv\\w*|timid\\w*|ansiedad|voz|rasgos personales)\\b");
	private static String fold(String text) {
		return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
				.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
	}
	public static boolean forbidden(String text) { return TOPICS.matcher(fold(text)).find(); }
	public static boolean personalComment(String text) { return forbidden(text) || TRAITS.matcher(fold(text)).find(); }
}
