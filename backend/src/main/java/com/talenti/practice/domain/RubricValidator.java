package com.talenti.practice.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Valida lo que devolvió la IA para una respuesta (012-18, 012-20, T4). La salida de la IA es un dato más: si viene
 * hostil, aquí se acota.
 * <ul>
 * <li>Solo cuentan los criterios de la rúbrica, una vez cada uno, con nivel 1 a 4; lo demás se descarta.</li>
 * <li>Cada cita debe ser un fragmento LITERAL de la transcripción final; si no, se descarta (máximo 3 por criterio).</li>
 * <li>Un comentario que hable del acento, la entonación, las emociones, los nervios, la apariencia o los rasgos de la
 * persona se descarta ({@link ForbiddenTopics#isForbiddenComment}).</li>
 * <li>El total lo calcula el código con los pesos de la rúbrica, y solo si están los cuatro criterios.</li>
 * </ul>
 * Código puro.
 */
public final class RubricValidator {

	public static final int MAX_QUOTES = 3;

	public static final int MAX_QUOTE_CHARS = 300;

	public static final int MAX_COMMENT_CHARS = 400;

	/** Un criterio ya validado. {@code comment} es nulo si se descartó. */
	public record ValidCriterion(String criterion, int level, String comment, List<String> quotes) {

		public ValidCriterion {
			quotes = List.copyOf(quotes);
		}

		@Override
		public String toString() {
			return "ValidCriterion[criterion=" + criterion + ", level=" + level + "]";
		}

	}

	/** @param total de 0 a 100, calculado por el código; nulo si falta algún criterio */
	public record Result(List<ValidCriterion> criteria, Integer total) {

		public Result {
			criteria = List.copyOf(criteria);
		}

		public boolean usable() {
			return !criteria.isEmpty();
		}

	}

	/** Lo que propuso la IA para un criterio. */
	public record Proposed(String criterion, Integer level, String comment, List<String> quotes) {

		@Override
		public String toString() {
			return "Proposed[...]";
		}

	}

	private final Rubric rubric;

	public RubricValidator(Rubric rubric) {
		this.rubric = rubric;
	}

	public Result validate(List<Proposed> proposed, String transcriptFinal) {
		String transcript = transcriptFinal == null ? "" : transcriptFinal;
		Map<String, ValidCriterion> accepted = new LinkedHashMap<>();
		for (Proposed item : proposed == null ? List.<Proposed>of() : proposed) {
			if (item == null || item.criterion() == null || item.level() == null) {
				continue;
			}
			String code = item.criterion().strip().toUpperCase(Locale.ROOT);
			if (!rubric.has(code) || accepted.containsKey(code) || item.level() < Rubric.MIN_LEVEL
					|| item.level() > Rubric.MAX_LEVEL) {
				continue;
			}
			accepted.put(code, new ValidCriterion(code, item.level(), comment(item.comment()),
					quotes(item.quotes(), transcript)));
		}
		List<ValidCriterion> ordered = new ArrayList<>();
		for (Rubric.Criterion criterion : rubric.criteria()) {
			if (accepted.containsKey(criterion.code())) {
				ordered.add(accepted.get(criterion.code()));
			}
		}
		return new Result(ordered, ordered.size() == rubric.criteria().size() ? total(ordered) : null);
	}

	/** Total de 0 a 100: suma de peso × nivel / 4 de cada criterio, redondeada. */
	private int total(List<ValidCriterion> criteria) {
		double sum = 0;
		for (ValidCriterion criterion : criteria) {
			sum += rubric.weight(criterion.criterion()) * (double) criterion.level() / Rubric.MAX_LEVEL;
		}
		return (int) Math.round(sum);
	}

	private static String comment(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		String comment = raw.strip();
		if (comment.length() > MAX_COMMENT_CHARS || ForbiddenTopics.isForbiddenComment(comment)) {
			return null;
		}
		return comment;
	}

	private static List<String> quotes(List<String> raw, String transcript) {
		List<String> result = new ArrayList<>();
		if (raw == null) {
			return result;
		}
		for (String quote : raw) {
			if (result.size() == MAX_QUOTES) {
				break;
			}
			if (quote == null) {
				continue;
			}
			String candidate = quote.strip();
			if (!candidate.isEmpty() && candidate.length() <= MAX_QUOTE_CHARS && transcript.contains(candidate)
					&& !result.contains(candidate)) {
				result.add(candidate);
			}
		}
		return result;
	}

}
