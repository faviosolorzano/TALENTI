package com.talenti.matching.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.talenti.matching.domain.CompatibilityResult.Outcome;

/**
 * La explicación por plantilla (015-10, RF-25): la misma forma que la de la IA, armada SOLO con lo que se enviaría a la
 * IA ({@link ExplanationRequest}) y los textos de {@code matching/dora-explanation.es.properties}. Sin IA, sin red y
 * determinista: mismos datos, mismo texto. Respeta los mismos largos que la validación y no escribe porcentajes.
 */
public final class ExplanationTemplate {

	private final Map<String, String> templates;

	public ExplanationTemplate(Map<String, String> templates) {
		this.templates = Map.copyOf(templates);
		version();
	}

	public String version() {
		return template("version");
	}

	public ExplanationContent write(ExplanationRequest request) {
		List<String> matchedSkills = new ArrayList<>();
		List<String> gapPhrases = new ArrayList<>();
		for (ExplanationRequest.Requirement r : request.requirements()) {
			if (r.outcome() == Outcome.MATCH) {
				matchedSkills.add(r.skill());
			}
			else {
				String kind = template("kind." + r.kind().name());
				gapPhrases.add(r.outcome() == Outcome.MISSING || r.profileLevel() == null
						? fill(template("gap.missing"), "skill", r.skill(), "kind", kind)
						: fill(template("gap.partial"), "skill", r.skill(), "kind", kind, "required", r.requiredLevel(),
								"profile", r.profileLevel()));
			}
		}
		List<String> matchedConditions = new ArrayList<>();
		for (ExplanationRequest.Condition c : request.conditions()) {
			if (c.outcome() == Outcome.MATCH) {
				matchedConditions.add(template("condition.match." + c.factor().name()));
			}
			else {
				gapPhrases.add(template("condition.gap." + c.factor().name()));
			}
		}

		String fit = matchedSkills.isEmpty() ? template("fit.none")
				: fill(template("fit.skills"), "skills", join(matchedSkills, 220));
		if (!matchedConditions.isEmpty()) {
			fit = fit + " " + fill(template("fit.conditions"), "conditions", join(matchedConditions, 120));
		}

		String gaps = gapPhrases.isEmpty() ? null
				: fit(template("gaps.intro") + " " + String.join(" ", gapPhrases), ExplanationValidator.MAX_SECTION);

		List<String> missing = new ArrayList<>();
		for (String key : request.missingInfo()) {
			missing.add(template("missing." + key));
		}
		String unknown = missing.isEmpty() ? null
				: fit(fill(template("unknown"), "items", join(missing, 300)), ExplanationValidator.MAX_SECTION);

		List<ExplanationContent.Practice> practice = new ArrayList<>();
		for (ExplanationRequest.PriorityGap gap : request.priorityGaps()) {
			String level = ExplanationRequest.levelText(gap.requiredLevel());
			practice.add(new ExplanationContent.Practice(gap.slug(), List.of(
					fit(fill(template("practice.review"), "skill", gap.skill(), "required", level),
							ExplanationValidator.MAX_ACTION),
					fit(fill(template("practice.exercise"), "skill", gap.skill()), ExplanationValidator.MAX_ACTION))));
		}
		return new ExplanationContent(fit(fit, ExplanationValidator.MAX_SECTION), gaps, unknown, practice);
	}

	/** "A, B y C"; si no cabe en {@code budget}, "A, B y 3 más". */
	private String join(List<String> items, int budget) {
		if (items.size() == 1) {
			return items.getFirst();
		}
		String all = String.join(", ", items.subList(0, items.size() - 1)) + " " + template("and") + " " + items.getLast();
		if (all.length() <= budget) {
			return all;
		}
		List<String> shown = new ArrayList<>();
		int length = 0;
		for (String item : items) {
			if (length + item.length() + 2 > budget - 20 && !shown.isEmpty()) {
				break;
			}
			shown.add(item);
			length += item.length() + 2;
		}
		int rest = items.size() - shown.size();
		return String.join(", ", shown) + " " + fill(template("and.more"), "count", Integer.toString(rest));
	}

	/** Corta en el último espacio antes del límite (solo pasa con muchísimos requisitos). */
	private static String fit(String text, int max) {
		if (text.length() <= max) {
			return text;
		}
		int cut = text.lastIndexOf(' ', max - 1);
		return text.substring(0, cut > 0 ? cut : max - 1).stripTrailing() + "…";
	}

	private String template(String key) {
		String value = templates.get(key);
		if (value == null) {
			throw new IllegalStateException("Falta la plantilla de la explicación de DORA: " + key);
		}
		return value;
	}

	private static String fill(String template, String... keyValues) {
		String text = template;
		for (int i = 0; i < keyValues.length; i += 2) {
			text = text.replace("{" + keyValues[i] + "}", keyValues[i + 1]);
		}
		return text;
	}

}
