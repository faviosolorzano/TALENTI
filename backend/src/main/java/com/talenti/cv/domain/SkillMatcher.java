package com.talenti.cv.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.talenti.vacancy.SkillInfo;

/**
 * Encuentra habilidades del catálogo en una línea de texto por su nombre o sus sinónimos, como PALABRA COMPLETA y sin
 * distinguir mayúsculas ni tildes (009-12): "Java" no se encuentra dentro de "JavaScript", ni "Git" dentro de
 * "Digital". Una palabra completa es una que no está pegada a una letra o un número.
 */
public final class SkillMatcher {

	private record Term(SkillInfo skill, Pattern pattern) {
	}

	private final List<Term> terms = new ArrayList<>();

	public SkillMatcher(List<SkillInfo> catalog) {
		for (SkillInfo skill : catalog) {
			Set<String> names = new LinkedHashSet<>();
			names.add(Fold.of(skill.name()));
			skill.aliases().forEach(alias -> names.add(Fold.of(alias)));
			for (String name : names) {
				if (name.length() >= 2) {
					terms.add(new Term(skill, Pattern
							.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(name) + "(?![\\p{L}\\p{N}])")));
				}
			}
		}
	}

	/** Las habilidades del catálogo que aparecen en la línea, en el orden del catálogo y sin repetir. */
	public List<SkillInfo> find(String line) {
		String folded = Fold.of(line);
		List<SkillInfo> found = new ArrayList<>();
		for (Term term : terms) {
			if (!found.contains(term.skill()) && term.pattern().matcher(folded).find()) {
				found.add(term.skill());
			}
		}
		return found;
	}

}
