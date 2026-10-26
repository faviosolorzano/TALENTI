package com.talenti.github.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Detección por reglas de las tecnologías de los repositorios <strong>elegidos</strong> (013-10). Sin IA: cada lenguaje
 * con al menos el porcentaje mínimo en algún repositorio elegido y cada tema se resuelven contra el catálogo; lo que
 * no está en el catálogo queda como {@code unmatched} y no se puede confirmar (RN-03). Código puro.
 */
public final class TechnologyDetector {

	/** Rótulo de la fuente de las sugerencias: reglas, no IA (013-11). */
	public static final String SOURCE = "RULES";

	public enum Source {
		LANGUAGE, TOPIC
	}

	/** Un repositorio elegido, con sus lenguajes ya calculados y sus temas. */
	public record SelectedRepo(String name, List<LanguageShare> languages, List<String> topics) {

		public SelectedRepo {
			languages = languages == null ? List.of() : List.copyOf(languages);
			topics = topics == null ? List.of() : List.copyOf(topics);
		}

		@Override
		public String toString() {
			return "SelectedRepo[...]";
		}

	}

	/** Una habilidad del catálogo. */
	public record CatalogSkill(String slug, String name) {
	}

	/** De dónde sale una tecnología: el repositorio y el lenguaje (con su porcentaje) o el tema. */
	public record Evidence(String repo, Source source, BigDecimal share) {

		@Override
		public String toString() {
			return "Evidence[source=" + source + "]";
		}

	}

	public record Suggestion(String slug, String name, List<Evidence> evidence) {

		@Override
		public String toString() {
			return "Suggestion[slug=" + slug + "]";
		}

	}

	public record Unmatched(String name, List<Evidence> evidence) {

		@Override
		public String toString() {
			return "Unmatched[...]";
		}

	}

	public record Detection(List<Suggestion> suggestions, List<Unmatched> unmatched) {
	}

	private final BigDecimal minLanguageShare;

	private final Function<String, Optional<CatalogSkill>> resolver;

	/**
	 * @param minLanguageShare porcentaje mínimo (10 por defecto)
	 * @param resolver         nombre de GitHub → habilidad del catálogo (mapa versionado más sinónimos del catálogo)
	 */
	public TechnologyDetector(double minLanguageShare, Function<String, Optional<CatalogSkill>> resolver) {
		this.minLanguageShare = BigDecimal.valueOf(minLanguageShare);
		this.resolver = resolver;
	}

	public Detection detect(List<SelectedRepo> repos) {
		Map<String, CatalogSkill> skills = new LinkedHashMap<>();
		Map<String, List<Evidence>> matched = new LinkedHashMap<>();
		Map<String, String> unmatchedNames = new LinkedHashMap<>();
		Map<String, List<Evidence>> unmatched = new LinkedHashMap<>();
		for (SelectedRepo repo : repos) {
			for (LanguageShare language : repo.languages()) {
				if (language.percent().compareTo(minLanguageShare) >= 0) {
					add(language.name(), new Evidence(repo.name(), Source.LANGUAGE, language.percent()), skills,
							matched, unmatchedNames, unmatched);
				}
			}
			for (String topic : repo.topics()) {
				add(topic, new Evidence(repo.name(), Source.TOPIC, null), skills, matched, unmatchedNames, unmatched);
			}
		}
		List<Suggestion> suggestions = new ArrayList<>();
		matched.forEach((slug, evidence) -> suggestions
				.add(new Suggestion(slug, skills.get(slug).name(), List.copyOf(evidence))));
		suggestions.sort(Comparator.comparing(Suggestion::name, String.CASE_INSENSITIVE_ORDER));
		List<Unmatched> rest = new ArrayList<>();
		unmatched.forEach((key, evidence) -> rest.add(new Unmatched(unmatchedNames.get(key), List.copyOf(evidence))));
		rest.sort(Comparator.comparing(Unmatched::name, String.CASE_INSENSITIVE_ORDER));
		return new Detection(List.copyOf(suggestions), List.copyOf(rest));
	}

	private void add(String rawName, Evidence evidence, Map<String, CatalogSkill> skills,
			Map<String, List<Evidence>> matched, Map<String, String> unmatchedNames,
			Map<String, List<Evidence>> unmatched) {
		String name = rawName == null ? "" : rawName.strip();
		if (name.isEmpty()) {
			return;
		}
		Optional<CatalogSkill> skill = resolver.apply(name);
		if (skill.isPresent()) {
			skills.putIfAbsent(skill.get().slug(), skill.get());
			addUnique(matched.computeIfAbsent(skill.get().slug(), key -> new ArrayList<>()), evidence);
		}
		else {
			String key = name.toLowerCase(Locale.ROOT);
			unmatchedNames.putIfAbsent(key, name);
			addUnique(unmatched.computeIfAbsent(key, k -> new ArrayList<>()), evidence);
		}
	}

	private static void addUnique(List<Evidence> list, Evidence evidence) {
		if (!list.contains(evidence)) {
			list.add(evidence);
		}
	}

}
