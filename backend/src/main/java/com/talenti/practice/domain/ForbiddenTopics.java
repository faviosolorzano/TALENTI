package com.talenti.practice.domain;

import java.util.regex.Pattern;

/**
 * Filtros de equidad (RN-09, T4, principio 3), código puro y sin IA:
 * <ul>
 * <li>{@link #isForbiddenQuestion}: una pregunta no puede tocar estado civil, familia, salud, religión, origen,
 * orientación, género, edad, política ni apariencia (012-03).</li>
 * <li>{@link #isForbiddenComment}: un comentario de retroalimentación no puede hablar del acento, la entonación, las
 * emociones, los nervios, la apariencia ni otros rasgos de la persona (012-18).</li>
 * </ul>
 * Se compara sin tildes y sin mayúsculas. Es deliberadamente estricto: ante la duda se descarta (la pregunta se
 * reemplaza por una del banco y el comentario se omite).
 */
public final class ForbiddenTopics {

	private static final Pattern QUESTION = Pattern.compile("\\b(estado civil|casad[oa]s?|solter[oa]s?|divorci\\w*"
			+ "|novi[oa]s?|hij[oa]s?|embaraz\\w*|maternidad|paternidad|salud|enfermedad\\w*|discapacidad\\w*"
			+ "|medicamento\\w*|religi\\w*|iglesia|creyente|dios|lugar de origen|pais de origen|origen etnico"
			+ "|de donde eres|nacionalidad|raza|racial|etnia|etnic[oa]|indigena|orientacion sexual|sexualidad"
			+ "|homosexual|heterosexual|gay|lesbiana|genero|edad|cuantos anos tienes|partido politico|politica"
			+ "|sindicat\\w*|apariencia|peso corporal|estatura|acento)\\b");

	private static final Pattern COMMENT = Pattern.compile("\\b(acento\\w*|entonacion\\w*|pronunci\\w*|tono de voz"
			+ "|tu voz|la voz|emocion\\w*|sentimient\\w*|nervio\\w*|ansiedad|ansios[oa]|estresad[oa]|estres|timid\\w*"
			+ "|insegur\\w*|apariencia|aspecto fisico|vestimenta|ropa|rasgo\\w*|cara|rostro|sonris\\w*|mirada"
			+ "|edad|genero|origen|personalidad|caracter|actitud)\\b");

	private ForbiddenTopics() {
	}

	public static boolean isForbiddenQuestion(String question) {
		return QUESTION.matcher(Folding.fold(question)).find();
	}

	public static boolean isForbiddenComment(String comment) {
		return COMMENT.matcher(Folding.fold(comment)).find();
	}

}
