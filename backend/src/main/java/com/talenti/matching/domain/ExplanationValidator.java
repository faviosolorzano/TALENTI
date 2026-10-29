package com.talenti.matching.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.talenti.matching.domain.CompatibilityResult.Outcome;

/**
 * Valida lo que propone la IA antes de mostrarlo (015-06 a 015-08). Si algo falla, la explicación entera se descarta y
 * se usa la plantilla ({@code INVALID_OUTPUT}, RF-25): nunca se corrige ni se muestra a medias.
 *
 * <p>Contenido (015-07): cada habilidad del catálogo que aparece (por nombre o sinónimo, sin mayúsculas ni tildes) debe
 * estar en el resultado: en {@code fit} solo coincidencias, en {@code gaps} solo brechas y en el resto cualquiera del
 * resultado; {@code practice} cubre EXACTAMENTE las brechas prioritarias, en su orden. Forma (015-08): ningún número con
 * "%" ni "por ciento" (ni el signo suelto), ninguna frase de la lista versionada (promesas, decisiones y atributos
 * personales), ni correos, enlaces o teléfonos, y los largos de la spec.
 */
public final class ExplanationValidator {

	public static final int MAX_SECTION = 400;

	public static final int MAX_ACTION = 160;

	public static final int MAX_ACTIONS = 3;

	/** Por qué se rechazó (solo para pruebas y métricas: nunca lleva el texto). */
	public enum Rejection {

		MISSING_SECTION, TOO_LONG, PERCENT, FORBIDDEN_PHRASE, CONTACT, SKILL_OUTSIDE_RESULT, PRACTICE_MISMATCH,
		ACTIONS_COUNT

	}

	/** El resultado: {@code content} si pasó, o el motivo del rechazo. */
	public record Verdict(ExplanationContent content, Rejection rejection) {

		public boolean valid() {
			return content != null;
		}

	}

	private static final Pattern PERCENT = Pattern.compile("%|\\bpor\\s*ciento\\b|\\bporciento\\b");

	private static final Pattern EMAIL = Pattern.compile("[\\p{L}\\p{N}._%+-]+@[\\p{L}\\p{N}.-]+");

	private static final Pattern LINK = Pattern.compile(
			"https?://|\\bwww\\.|\\b[\\p{L}\\p{N}-]+\\.(com|pe|org|net|io|dev|edu|gob|app|ai|co|info|me|xyz|ly)\\b");

	/** Ocho o más dígitos seguidos (con espacios, guiones o puntos entre ellos), con o sin prefijo "+". */
	private static final Pattern PHONE = Pattern.compile("\\+?\\d(?:[\\s.-]?\\d){7,}");

	private final List<String> forbiddenPhrases;

	private final String forbiddenVersion;

	/**
	 * @param forbiddenPhrases frases prohibidas (se comparan sin mayúsculas ni tildes, como palabras completas)
	 * @param forbiddenVersion versión de la lista (principio 11)
	 */
	public ExplanationValidator(List<String> forbiddenPhrases, String forbiddenVersion) {
		List<String> normalized = new ArrayList<>();
		for (String phrase : forbiddenPhrases) {
			String n = normalize(phrase);
			if (!n.isBlank()) {
				normalized.add(n);
			}
		}
		if (normalized.isEmpty()) {
			throw new IllegalArgumentException("La lista de frases prohibidas está vacía");
		}
		this.forbiddenPhrases = List.copyOf(normalized);
		this.forbiddenVersion = forbiddenVersion;
	}

	public String forbiddenVersion() {
		return forbiddenVersion;
	}

	/**
	 * @param catalog todas las habilidades del catálogo vigente con sus sinónimos; se suman los nombres del resultado por
	 *                si alguna salió del catálogo (se compara por {@code slug})
	 */
	public Verdict validate(ExplanationDraft draft, ExplanationRequest request, List<SkillTerms> catalog) {
		if (draft == null || draft.fit() == null || draft.fit().isBlank() || draft.practice() == null) {
			return reject(Rejection.MISSING_SECTION);
		}
		String fit = draft.fit().strip();
		String gaps = blankToNull(draft.gaps());
		String unknown = blankToNull(draft.unknown());

		// 015-07: la práctica cubre exactamente las brechas prioritarias, en su orden.
		List<String> expected = request.priorityGapSlugs();
		if (draft.practice().size() != expected.size()) {
			return reject(Rejection.PRACTICE_MISMATCH);
		}
		List<ExplanationContent.Practice> practice = new ArrayList<>();
		List<String> actionsText = new ArrayList<>();
		for (int i = 0; i < expected.size(); i++) {
			ExplanationDraft.Practice item = draft.practice().get(i);
			if (item == null || item.skillSlug() == null || !expected.get(i).equals(item.skillSlug().strip())) {
				return reject(Rejection.PRACTICE_MISMATCH);
			}
			if (item.actions() == null || item.actions().isEmpty() || item.actions().size() > MAX_ACTIONS) {
				return reject(Rejection.ACTIONS_COUNT);
			}
			List<String> actions = new ArrayList<>();
			for (String action : item.actions()) {
				if (action == null || action.isBlank()) {
					return reject(Rejection.ACTIONS_COUNT);
				}
				if (action.strip().length() > MAX_ACTION) {
					return reject(Rejection.TOO_LONG);
				}
				actions.add(action.strip());
			}
			actionsText.addAll(actions);
			practice.add(new ExplanationContent.Practice(expected.get(i), actions));
		}

		// 015-08: largos.
		for (String text : new String[] { fit, gaps, unknown }) {
			if (text != null && text.length() > MAX_SECTION) {
				return reject(Rejection.TOO_LONG);
			}
		}

		Map<String, List<Pattern>> terms = termPatterns(catalog, request);
		List<String> all = new ArrayList<>();
		all.add(fit);
		if (gaps != null) {
			all.add(gaps);
		}
		if (unknown != null) {
			all.add(unknown);
		}
		all.addAll(actionsText);

		// 015-08: forma.
		for (String text : all) {
			Rejection shape = shape(text, terms);
			if (shape != null) {
				return reject(shape);
			}
		}

		// 015-07: cada sección menciona solo habilidades que le corresponden.
		Set<String> matches = new HashSet<>();
		Set<String> gapSlugs = new HashSet<>();
		Set<String> inResult = new HashSet<>();
		for (ExplanationRequest.Requirement requirement : request.requirements()) {
			inResult.add(requirement.slug());
			(requirement.outcome() == Outcome.MATCH ? matches : gapSlugs).add(requirement.slug());
		}
		if (!matches.containsAll(mentions(fit, terms))) {
			return reject(Rejection.SKILL_OUTSIDE_RESULT);
		}
		if (gaps != null && !gapSlugs.containsAll(mentions(gaps, terms))) {
			return reject(Rejection.SKILL_OUTSIDE_RESULT);
		}
		List<String> rest = new ArrayList<>(actionsText);
		if (unknown != null) {
			rest.add(unknown);
		}
		for (String text : rest) {
			if (!inResult.containsAll(mentions(text, terms))) {
				return reject(Rejection.SKILL_OUTSIDE_RESULT);
			}
		}
		return new Verdict(new ExplanationContent(fit, gaps, unknown, practice), null);
	}

	// ----------------------------------------------------------------------------------------------- internos

	private Rejection shape(String text, Map<String, List<Pattern>> terms) {
		String normalized = normalize(text);
		if (PERCENT.matcher(normalized).find()) {
			return Rejection.PERCENT;
		}
		for (String phrase : forbiddenPhrases) {
			if (word(phrase).matcher(normalized).find()) {
				return Rejection.FORBIDDEN_PHRASE;
			}
		}
		if (EMAIL.matcher(normalized).find() || PHONE.matcher(normalized).find()) {
			return Rejection.CONTACT;
		}
		var links = LINK.matcher(normalized);
		while (links.find()) {
			String candidate = links.group();
			// ASP.NET es un nombre completo del catálogo; git.dev sigue siendo un enlace,
			// aunque "Git" por separado sea una habilidad permitida.
			boolean skillName = terms.values().stream().flatMap(List::stream)
					.anyMatch(pattern -> pattern.matcher(candidate).matches());
			if (!skillName) return Rejection.CONTACT;
		}
		return null;
	}

	private static Set<String> mentions(String text, Map<String, List<Pattern>> terms) {
		String normalized = normalize(text);
		record Mention(String slug, int start, int end) { }
		List<Mention> mentions = new ArrayList<>();
		for (Map.Entry<String, List<Pattern>> entry : terms.entrySet()) {
			for (Pattern pattern : entry.getValue()) {
				var matcher = pattern.matcher(normalized);
				while (matcher.find()) mentions.add(new Mention(entry.getKey(), matcher.start(), matcher.end()));
			}
		}
		// El nombre más largo tiene precedencia: "JS" dentro de "Node.js" no menciona JavaScript.
		mentions.sort(java.util.Comparator.comparingInt((Mention m) -> m.end() - m.start()).reversed());
		boolean[] occupied = new boolean[normalized.length()];
		Set<String> found = new HashSet<>();
		for (Mention mention : mentions) {
			boolean overlaps = false;
			for (int i = mention.start(); i < mention.end(); i++) overlaps |= occupied[i];
			if (overlaps) continue;
			found.add(mention.slug());
			java.util.Arrays.fill(occupied, mention.start(), mention.end(), true);
		}
		return found;
	}

	/** Por {@code slug}: patrones del nombre y los sinónimos, del más largo al más corto. */
	private static Map<String, List<Pattern>> termPatterns(List<SkillTerms> catalog, ExplanationRequest request) {
		Map<String, Set<String>> bySlug = new LinkedHashMap<>();
		for (SkillTerms skill : catalog) {
			bySlug.computeIfAbsent(skill.slug(), slug -> new HashSet<>()).addAll(skill.terms());
		}
		for (ExplanationRequest.Requirement requirement : request.requirements()) {
			bySlug.computeIfAbsent(requirement.slug(), slug -> new HashSet<>()).add(requirement.skill());
		}
		Map<String, List<Pattern>> patterns = new LinkedHashMap<>();
		for (Map.Entry<String, Set<String>> entry : bySlug.entrySet()) {
			List<String> normalized = new ArrayList<>();
			for (String term : entry.getValue()) {
				String n = normalize(term);
				if (!n.isBlank()) {
					normalized.add(n);
				}
			}
			normalized.sort((a, b) -> Integer.compare(b.length(), a.length()));
			patterns.put(entry.getKey(), normalized.stream().map(ExplanationValidator::word).toList());
		}
		return patterns;
	}

	/** Un término como palabra completa: ni letra ni dígito justo antes o justo después. */
	private static Pattern word(String normalizedTerm) {
		return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(normalizedTerm) + "(?![\\p{L}\\p{N}])");
	}

	/** Sin tildes, en minúsculas y con los espacios reducidos a uno. */
	static String normalize(String text) {
		String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return decomposed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text.strip();
	}

	private static Verdict reject(Rejection rejection) {
		return new Verdict(null, rejection);
	}

}
