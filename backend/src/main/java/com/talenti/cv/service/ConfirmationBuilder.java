package com.talenti.cv.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.talenti.cv.persistence.SuggestionRecord;
import com.talenti.profile.ConfirmedChanges;
import com.talenti.shared.web.FieldViolation;

import tools.jackson.databind.JsonNode;

/**
 * Convierte las decisiones de la persona en los cambios que se aplican al perfil (009-15). Lo omitido se descarta; lo
 * aceptado y lo editado se mezclan con el valor sugerido ({@code value} manda sobre la sugerencia) y se validan por
 * {@code ProfileEditor} con las reglas de 006. Recuerda de qué decisión salió cada habilidad, proyecto y dato
 * académico para devolver los errores con la ruta del campo de la solicitud (por ejemplo
 * {@code decisions[2].value.level}).
 */
final class ConfirmationBuilder {

	static final int MAX_DECISIONS = 200;

	private static final Pattern SKILL_PATH = Pattern.compile("^skills\\[(\\d+)](.*)$");

	private static final Pattern PROJECT_PATH = Pattern.compile("^projects\\[(\\d+)](.*)$");

	private final List<FieldViolation> violations = new ArrayList<>();

	private final List<Integer> skillDecisions = new ArrayList<>();

	private final List<Integer> projectDecisions = new ArrayList<>();

	private final Map<String, Integer> academicDecisions = new HashMap<>();

	private final List<ConfirmedChanges.Skill> skills = new ArrayList<>();

	private final List<ConfirmedChanges.Project> projects = new ArrayList<>();

	private String career;

	private String institute;

	private Integer cycle;

	private boolean hasAcademic;

	private int accepted;

	private int edited;

	private int discarded;

	ConfirmationBuilder(List<DecisionInput> decisions, List<SuggestionRecord> suggestions) {
		Map<UUID, SuggestionRecord> byId = new HashMap<>();
		suggestions.forEach(suggestion -> byId.put(suggestion.id(), suggestion));
		Set<UUID> seen = new HashSet<>();
		for (int i = 0; i < decisions.size(); i++) {
			add(i, decisions.get(i), byId, seen);
		}
		discarded += byId.size() - seen.size();
	}

	List<FieldViolation> violations() {
		return violations;
	}

	int accepted() {
		return accepted;
	}

	int edited() {
		return edited;
	}

	int discarded() {
		return discarded;
	}

	/** Si hay algo que aplicar al perfil. */
	boolean hasChanges() {
		return hasAcademic || !skills.isEmpty() || !projects.isEmpty();
	}

	ConfirmedChanges changes() {
		ConfirmedChanges.Academic academic = hasAcademic ? new ConfirmedChanges.Academic(career, institute, cycle)
				: null;
		return new ConfirmedChanges(academic, skills, projects);
	}

	/** Traduce el error del perfil ({@code skills[1].level}) a la ruta de la solicitud ({@code decisions[3].value.level}). */
	List<FieldViolation> remap(List<FieldViolation> fromProfile) {
		List<FieldViolation> result = new ArrayList<>();
		for (FieldViolation violation : fromProfile) {
			result.add(new FieldViolation(remap(violation.field()), violation.rule(), violation.args()));
		}
		return result;
	}

	private String remap(String field) {
		Matcher skill = SKILL_PATH.matcher(field);
		if (skill.matches()) {
			int index = Integer.parseInt(skill.group(1));
			if (index < skillDecisions.size()) {
				return "decisions[" + skillDecisions.get(index) + "].value" + skill.group(2);
			}
		}
		Matcher project = PROJECT_PATH.matcher(field);
		if (project.matches()) {
			int index = Integer.parseInt(project.group(1));
			if (index < projectDecisions.size()) {
				return "decisions[" + projectDecisions.get(index) + "].value" + project.group(2);
			}
		}
		if (field.startsWith("academic.")) {
			Integer decision = academicDecisions.get(field.substring("academic.".length()));
			if (decision != null) {
				return "decisions[" + decision + "].value.value";
			}
		}
		return "decisions";
	}

	// ------------------------------------------------------------------------------------------ decisión por decisión

	private void add(int index, DecisionInput input, Map<UUID, SuggestionRecord> byId, Set<UUID> seen) {
		String base = "decisions[" + index + "]";
		if (input == null) {
			violations.add(FieldViolation.of(base, "required"));
			return;
		}
		UUID id = parseId(input.suggestionId());
		SuggestionRecord suggestion = id == null ? null : byId.get(id);
		if (suggestion == null) {
			violations.add(FieldViolation.of(base + ".suggestionId", "unknown-suggestion"));
			return;
		}
		if (!seen.add(id)) {
			violations.add(FieldViolation.of(base + ".suggestionId", "duplicate"));
			return;
		}
		String action = input.action() == null ? "" : input.action().strip();
		switch (action) {
			case "DISCARD" -> discarded++;
			case "ACCEPT" -> {
				accepted++;
				apply(index, suggestion, input.value());
			}
			case "EDIT" -> {
				edited++;
				if (input.value() == null || !input.value().isObject()) {
					violations.add(FieldViolation.of(base + ".value", "required"));
				}
				else {
					apply(index, suggestion, input.value());
				}
			}
			default -> violations.add(FieldViolation.of(base + ".action", "invalid-action"));
		}
	}

	private void apply(int index, SuggestionRecord suggestion, JsonNode value) {
		String base = "decisions[" + index + "].value";
		if (value != null && !value.isNull() && !value.isObject()) {
			violations.add(FieldViolation.of(base, "invalid"));
			return;
		}
		JsonNode payload = suggestion.payload();
		switch (suggestion.kind()) {
			case SKILL -> {
				String slug = text(value, payload, "slug");
				Integer level = integer(base + ".level", value, payload, "level");
				skillDecisions.add(index);
				skills.add(new ConfirmedChanges.Skill(slug, level));
			}
			case PROJECT -> {
				projectDecisions.add(index);
				projects.add(new ConfirmedChanges.Project(text(value, payload, "name"),
						text(value, payload, "description"), technologies(base, value, payload)));
			}
			case ACADEMIC -> {
				hasAcademic = true;
				academicDecisions.put(fieldKey(suggestion.field()), index);
				switch (suggestion.field()) {
					case "CAREER" -> career = text(value, payload, "value");
					case "INSTITUTE" -> institute = text(value, payload, "value");
					default -> cycle = integer(base + ".value", value, payload, "value");
				}
			}
		}
	}

	private static String fieldKey(String field) {
		return switch (field) {
			case "CAREER" -> "career";
			case "INSTITUTE" -> "institute";
			default -> "cycle";
		};
	}

	/** El campo de texto que manda: el de {@code value} si viene, si no el de la sugerencia. */
	private static String text(JsonNode value, JsonNode payload, String field) {
		JsonNode chosen = value != null && value.has(field) ? value.get(field) : payload.get(field);
		return chosen == null || chosen.isNull() || !chosen.isString() ? null : chosen.asString();
	}

	private Integer integer(String fieldPath, JsonNode value, JsonNode payload, String field) {
		JsonNode chosen = value != null && value.has(field) ? value.get(field) : payload.get(field);
		if (chosen == null || chosen.isNull()) {
			return null;
		}
		if (chosen.isIntegralNumber() && chosen.canConvertToInt()) {
			return chosen.intValue();
		}
		if (chosen.isString()) {
			try {
				return Integer.valueOf(chosen.asString().strip());
			}
			catch (NumberFormatException e) {
				// cae al error de abajo
			}
		}
		violations.add(FieldViolation.of(fieldPath, "invalid"));
		return null;
	}

	private List<String> technologies(String base, JsonNode value, JsonNode payload) {
		JsonNode chosen = value != null && value.has("technologies") ? value.get("technologies")
				: payload.get("technologies");
		if (chosen == null || !chosen.isArray()) {
			return List.of();
		}
		List<String> result = new ArrayList<>();
		for (JsonNode item : chosen) {
			if (!item.isString()) {
				violations.add(FieldViolation.of(base + ".technologies", "invalid"));
				return List.of();
			}
			result.add(item.asString());
		}
		return result;
	}

	private static UUID parseId(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			return UUID.fromString(raw.strip());
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

}
