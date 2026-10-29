package com.talenti.matching.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.talenti.matching.domain.CompatibilityResult.ConditionItem;
import com.talenti.matching.domain.CompatibilityResult.Item;
import com.talenti.matching.domain.CompatibilityResult.Outcome;
import com.talenti.matching.domain.CompatibilityResult.ProfileItem;
import com.talenti.matching.domain.CompatibilityResult.RequirementItem;

/**
 * Lo ÚNICO que viaja a la IA para redactar la explicación (015-04): el título de la vacante, el veredicto, si hubo tope,
 * la base de información, los requisitos (nombre de catálogo, tipo, niveles como texto y resultado), las condiciones
 * (solo factor y resultado: ni la ciudad ni las horas), los datos faltantes por clave y las brechas prioritarias
 * (015-09). No hay campo para el porcentaje, el nombre, el contacto, el instituto, la carrera, el ciclo, "sobre mí", el
 * CV, GitHub ni las sesiones de práctica (T4, T5): no se pueden enviar porque no existen aquí.
 *
 * <p>{@link #json()} es la forma canónica (mismo orden, mismo texto) que va a la IA como dato delimitado y de la que sale
 * la huella del caché (015-12). Se arma a mano para que este paquete siga siendo código puro.
 */
public record ExplanationRequest(String vacancyTitle, String verdict, boolean capped, Integer informationBasis,
		List<Requirement> requirements, List<Condition> conditions, List<String> missingInfo,
		List<PriorityGap> priorityGaps) {

	/** Brechas prioritarias como máximo (015-09). */
	public static final int MAX_PRIORITY_GAPS = 3;

	public ExplanationRequest {
		requirements = List.copyOf(requirements);
		conditions = List.copyOf(conditions);
		missingInfo = List.copyOf(missingInfo);
		priorityGaps = List.copyOf(priorityGaps);
	}

	public record Requirement(String slug, String skill, RequirementKind kind, String requiredLevel,
			String profileLevel, Outcome outcome) {
	}

	public record Condition(CompatibilityResult.Factor factor, Outcome outcome) {
	}

	/** Una brecha que lleva práctica: un requisito ausente o parcial. */
	public record PriorityGap(String slug, String skill, RequirementKind kind, int requiredLevel, Integer profileLevel,
			Outcome outcome) {
	}

	/** Solo para un resultado calculado (RN-02): con uno insuficiente no hay explicación. */
	public static ExplanationRequest of(String vacancyTitle, CompatibilityResult result) {
		if (!result.calculated()) {
			throw new IllegalArgumentException("Solo se explica un resultado calculado");
		}
		List<Requirement> requirements = new ArrayList<>();
		List<Condition> conditions = new ArrayList<>();
		List<Item> all = new ArrayList<>(result.matches());
		all.addAll(result.gaps());
		for (Item item : all) {
			if (item instanceof RequirementItem r) {
				requirements.add(new Requirement(r.slug(), r.name(), r.kind(), levelText(r.requiredLevel()),
						r.profileLevel() == null ? null : levelText(r.profileLevel()), r.outcome()));
			}
			else if (item instanceof ConditionItem c) {
				conditions.add(new Condition(c.factor(), c.outcome()));
			}
		}
		List<String> missing = new ArrayList<>();
		for (Item item : result.missingInfo()) {
			if (item instanceof ConditionItem c) {
				missing.add(c.factor().name());
			}
			else if (item instanceof ProfileItem p) {
				missing.add(p.key().name());
			}
		}
		return new ExplanationRequest(vacancyTitle == null ? "" : vacancyTitle.strip(), result.level().name(),
				result.capped(), result.informationBasis(), requirements, conditions, missing,
				priorityGaps(result.gaps()));
	}

	/**
	 * 015-09: hasta 3 requisitos en este orden: obligatorio ausente, obligatorio parcial, deseable ausente, deseable
	 * parcial; a igualdad, por nombre (y por {@code slug} si el nombre coincide). Las condiciones no llevan práctica.
	 */
	public static List<PriorityGap> priorityGaps(List<Item> gaps) {
		List<RequirementItem> candidates = new ArrayList<>();
		for (Item item : gaps) {
			if (item instanceof RequirementItem r && (r.outcome() == Outcome.MISSING || r.outcome() == Outcome.PARTIAL)) {
				candidates.add(r);
			}
		}
		candidates.sort(Comparator.comparingInt(ExplanationRequest::rank)
				.thenComparing(r -> r.name().toLowerCase(Locale.ROOT))
				.thenComparing(RequirementItem::slug));
		return candidates.stream().limit(MAX_PRIORITY_GAPS)
				.map(r -> new PriorityGap(r.slug(), r.name(), r.kind(), r.requiredLevel(), r.profileLevel(), r.outcome()))
				.toList();
	}

	private static int rank(RequirementItem r) {
		return (r.kind() == RequirementKind.REQUIRED ? 0 : 2) + (r.outcome() == Outcome.MISSING ? 0 : 1);
	}

	/** 1 básico, 2 intermedio, 3 avanzado (015-04). */
	public static String levelText(int level) {
		return switch (level) {
			case 1 -> "básico";
			case 2 -> "intermedio";
			case 3 -> "avanzado";
			default -> throw new IllegalArgumentException("Nivel fuera de rango");
		};
	}

	/** Los {@code slug} de las brechas prioritarias, en su orden. */
	public List<String> priorityGapSlugs() {
		return priorityGaps.stream().map(PriorityGap::slug).toList();
	}

	/** La forma canónica en JSON: lo que se envía y de lo que sale la huella. */
	public String json() {
		StringBuilder out = new StringBuilder(512);
		out.append("{\"vacancyTitle\":").append(quote(vacancyTitle));
		out.append(",\"verdict\":").append(quote(verdict));
		out.append(",\"capped\":").append(capped);
		out.append(",\"informationBasis\":").append(informationBasis == null ? "null" : informationBasis.toString());
		out.append(",\"requirements\":[");
		for (int i = 0; i < requirements.size(); i++) {
			Requirement r = requirements.get(i);
			out.append(i == 0 ? "" : ",").append("{\"skill\":").append(quote(r.skill()))
					.append(",\"kind\":").append(quote(r.kind().name()))
					.append(",\"requiredLevel\":").append(quote(r.requiredLevel()))
					.append(",\"profileLevel\":").append(r.profileLevel() == null ? "null" : quote(r.profileLevel()))
					.append(",\"outcome\":").append(quote(r.outcome().name())).append('}');
		}
		out.append("],\"conditions\":[");
		for (int i = 0; i < conditions.size(); i++) {
			Condition c = conditions.get(i);
			out.append(i == 0 ? "" : ",").append("{\"factor\":").append(quote(c.factor().name()))
					.append(",\"outcome\":").append(quote(c.outcome().name())).append('}');
		}
		out.append("],\"missingInfo\":[");
		for (int i = 0; i < missingInfo.size(); i++) {
			out.append(i == 0 ? "" : ",").append(quote(missingInfo.get(i)));
		}
		out.append("],\"priorityGaps\":[");
		for (int i = 0; i < priorityGaps.size(); i++) {
			PriorityGap g = priorityGaps.get(i);
			out.append(i == 0 ? "" : ",").append("{\"skillSlug\":").append(quote(g.slug()))
					.append(",\"skill\":").append(quote(g.skill()))
					.append(",\"kind\":").append(quote(g.kind().name()))
					.append(",\"outcome\":").append(quote(g.outcome().name())).append('}');
		}
		return out.append("]}").toString();
	}

	static String quote(String value) {
		StringBuilder out = new StringBuilder(value.length() + 2).append('"');
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				case '\r' -> out.append("\\r");
				case '\t' -> out.append("\\t");
				default -> {
					if (c < 0x20 || c == ' ' || c == ' ') {
						out.append(String.format("\\u%04x", (int) c));
					}
					else {
						out.append(c);
					}
				}
			}
		}
		return out.append('"').toString();
	}

	/** Lleva el título de la vacante y el resultado: no se imprime en registros. */
	@Override
	public String toString() {
		return "ExplanationRequest[requirements=" + requirements.size() + ", priorityGaps=" + priorityGaps.size() + "]";
	}

}
