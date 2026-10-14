package com.talenti.matching.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.talenti.matching.domain.CompatibilityResult.ConditionItem;
import com.talenti.matching.domain.CompatibilityResult.Item;
import com.talenti.matching.domain.CompatibilityResult.Outcome;
import com.talenti.matching.domain.CompatibilityResult.Reason;
import com.talenti.matching.domain.CompatibilityResult.RequirementItem;

/**
 * Arma la explicación por plantilla (007-13) a partir SOLO del resultado estructurado y de los textos de
 * {@code matching/explanation.es.properties}. No usa IA, no recibe el perfil y no califica a la persona: los únicos
 * datos que aparecen son nombres de habilidades del catálogo, niveles y cifras. Los mismos datos dan el mismo texto.
 */
public final class ExplanationWriter {

	private final Map<String, String> templates;

	private final int cap;

	public ExplanationWriter(Map<String, String> templates, int cap) {
		this.templates = Map.copyOf(templates);
		this.cap = cap;
	}

	public String version() {
		return template("version");
	}

	public Explanation write(CompatibilityResult result) {
		List<String> sentences = new ArrayList<>();
		if (!result.calculated()) {
			for (Reason reason : result.reasons()) {
				sentences.add(template("insufficient." + reason.name()));
			}
			return new Explanation(Explanation.TEMPLATE, version(), String.join(" ", sentences));
		}

		List<RequirementItem> all = new ArrayList<>();
		for (Item item : result.matches()) {
			if (item instanceof RequirementItem requirement) {
				all.add(requirement);
			}
		}
		for (Item item : result.gaps()) {
			if (item instanceof RequirementItem requirement) {
				all.add(requirement);
			}
		}
		long total = all.stream().filter(r -> r.kind() == RequirementKind.REQUIRED).count();
		long met = all.stream().filter(r -> r.kind() == RequirementKind.REQUIRED && r.outcome() == Outcome.MATCH)
				.count();
		if (total == 0) {
			sentences.add(template("required.none"));
		}
		else {
			sentences.add(fill(template(total == 1 ? "required.summary.one" : "required.summary"), "met",
					Long.toString(met), "total", Long.toString(total)));
		}

		RequirementItem main = mainGap(result.gaps());
		if (main != null) {
			sentences.add(describeGap(main));
		}
		boolean requiredMissing = result.gaps().stream()
				.anyMatch(item -> item instanceof RequirementItem r && r.kind() == RequirementKind.REQUIRED
						&& r.outcome() == Outcome.MISSING);
		if (requiredMissing) {
			sentences.add(fill(template("cap.warning"), "cap", Integer.toString(cap)));
		}
		if (result.informationBasis() != null && result.informationBasis() < 100) {
			sentences.add(fill(template("basis"), "basis", Integer.toString(result.informationBasis())));
		}
		List<String> missing = new ArrayList<>();
		for (Item item : result.missingInfo()) {
			if (item instanceof ConditionItem condition) {
				missing.add(template("missing." + condition.factor().name()));
			}
		}
		if (!missing.isEmpty()) {
			sentences.add(template("missing.intro") + " " + String.join(", ", missing) + ".");
		}
		return new Explanation(Explanation.TEMPLATE, version(), String.join(" ", sentences));
	}

	/** Primero un obligatorio ausente, luego uno parcial, luego un deseable (ausente antes que parcial). */
	private static RequirementItem mainGap(List<Item> gaps) {
		RequirementItem best = null;
		int bestRank = Integer.MAX_VALUE;
		for (Item item : gaps) {
			if (item instanceof RequirementItem requirement) {
				int rank = (requirement.kind() == RequirementKind.REQUIRED ? 0 : 2)
						+ (requirement.outcome() == Outcome.MISSING ? 0 : 1);
				if (rank < bestRank) {
					best = requirement;
					bestRank = rank;
				}
			}
		}
		return best;
	}

	private String describeGap(RequirementItem gap) {
		boolean required = gap.kind() == RequirementKind.REQUIRED;
		boolean missing = gap.outcome() == Outcome.MISSING;
		String key = "gap." + (required ? "required" : "desirable") + "." + (missing ? "missing" : "partial");
		return fill(template(key), "skill", gap.name(), "required", level(gap.requiredLevel()), "profile",
				gap.profileLevel() == null ? "" : level(gap.profileLevel()));
	}

	private String level(int level) {
		return template("level." + level);
	}

	private String template(String key) {
		String value = templates.get(key);
		if (value == null) {
			throw new IllegalStateException("Falta la plantilla de la explicación: " + key);
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
