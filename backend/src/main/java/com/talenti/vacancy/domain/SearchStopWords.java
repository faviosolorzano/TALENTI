package com.talenti.vacancy.domain;

import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Palabras de relleno de la búsqueda (004-26): las que se escriben al hablar ("se usar java", "quiero un practicante
 * de datos") pero no ayudan a encontrar una vacante. Se descartan antes de buscar; si no queda ninguna palabra útil,
 * {@code q} no filtra.
 *
 * <p>Es el único lugar donde vive la lista: para ampliarla basta añadir palabras a {@link #WORDS}. Se escriben en
 * minúsculas y sin tildes, igual que se comparan ({@link TextNormalizer}); aun así se normalizan al cargar, así que
 * un descuido con una tilde no rompe nada. No debe incluir nombres de tecnologías ni de empresas.
 */
public final class SearchStopWords {

	private static final String[] WORDS = {
			// Verbos y frases de quien busca ("sé usar", "manejo", "quiero", "busco")
			"se", "sabe", "saber", "sabes", "sabemos", "usar", "uso", "usa", "usando", "manejo", "manejar", "maneja",
			"conozco", "conocer", "conoce", "programar", "trabajar", "trabajo", "trabaja", "quiero", "quisiera", "busco",
			"buscar", "busca", "necesito", "tengo", "tener", "hacer", "hago", "soy", "ser", "es", "estoy", "estar",
			"aprender", "aprendo", "experiencia", "conocimiento", "conocimientos", "habilidad", "habilidades",
			// Lo que son todas las vacantes de Talenti
			"practicante", "practicantes", "practica", "practicas", "preprofesional", "preprofesionales", "pasante",
			"pasantes", "pasantia", "pasantias", "intern", "interns", "internship", "internships", "vacante", "vacantes",
			"oferta", "ofertas", "oportunidad", "oportunidades", "empleo", "empleos", "puesto", "puestos",
			// Artículos, preposiciones, conjunciones y pronombres
			"el", "la", "los", "las", "lo", "un", "una", "unos", "unas", "de", "del", "al", "en", "con", "sin", "para",
			"por", "sobre", "desde", "hasta", "entre", "hacia", "ante", "bajo", "tras", "y", "e", "o", "u", "ni", "que",
			"como", "cual", "cuales", "mi", "mis", "tu", "tus", "su", "sus", "me", "te", "nos", "algo", "mas", "muy",
			"ya", "no", "si" };

	private static final Set<String> NORMALIZED = Stream.of(WORDS).map(TextNormalizer::normalize)
			.collect(Collectors.toUnmodifiableSet());

	private SearchStopWords() {
	}

	/** Si la palabra (sin importar mayúsculas ni tildes) es de relleno. */
	public static boolean isStopWord(String word) {
		return NORMALIZED.contains(TextNormalizer.normalize(word));
	}

}
