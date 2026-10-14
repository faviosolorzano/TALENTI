package com.talenti.matching.domain;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado estructurado del cálculo (007-12). Todo el texto que ve la persona sale de aquí (la explicación se arma
 * solo con este resultado, 007-13).
 *
 * @param percent          entero de 0 a 100; nulo si {@code status} es {@code INSUFFICIENT}
 * @param rawPercent       antes de redondear y de aplicar el tope; nulo si es insuficiente (no sale por la API)
 * @param level            veredicto; nulo si es insuficiente
 * @param capped           verdadero solo si el tope bajó la cifra
 * @param informationBasis porcentaje de la información usada (RN-02); nulo si es insuficiente
 */
public record CompatibilityResult(Status status, List<Reason> reasons, Integer percent, BigDecimal rawPercent,
		Level level, boolean capped, Integer informationBasis, List<Component> components, List<Item> matches,
		List<Item> gaps, List<Item> missingInfo) {

	public CompatibilityResult {
		reasons = List.copyOf(reasons);
		components = List.copyOf(components);
		matches = List.copyOf(matches);
		gaps = List.copyOf(gaps);
		missingInfo = List.copyOf(missingInfo);
	}

	public boolean calculated() {
		return status == Status.CALCULATED;
	}

	public enum Status {

		CALCULATED, INSUFFICIENT

	}

	public enum Reason {

		NO_SKILLS, LOW_INFORMATION, VACANCY_WITHOUT_REQUIREMENTS

	}

	public enum Level {

		HIGH, MEDIUM, LOW

	}

	public enum ComponentKey {

		REQUIRED, DESIRABLE, CONDITIONS, SIMILARITY

	}

	public enum Outcome {

		MATCH, PARTIAL, MISSING, MISMATCH, UNKNOWN

	}

	public enum Factor {

		MODALITY, LOCATION, AVAILABILITY

	}

	public enum ProfileKey {

		SKILLS

	}

	/** Un componente de la fórmula: su peso configurado, su puntaje de 0 a 1 y si se pudo evaluar. */
	public record Component(ComponentKey key, int weight, BigDecimal score, boolean evaluable) {
	}

	/** Elemento de las listas {@code matches}, {@code gaps} y {@code missingInfo}. */
	public sealed interface Item permits RequirementItem, ConditionItem, ProfileItem {
	}

	/** Un requisito con su evidencia: la fuente del nivel es siempre {@code DECLARED} en el E2 (RN-03). */
	public record RequirementItem(String slug, String name, RequirementKind kind, int requiredLevel,
			Integer profileLevel, Outcome outcome) implements Item {
	}

	public record ConditionItem(Factor factor, Outcome outcome, String vacancyValue, String profileValue)
			implements Item {

		/** {@code profileValue} es un dato del perfil (ubicación, horas): no se imprime en registros. */
		@Override
		public String toString() {
			return "ConditionItem[factor=" + factor + ", outcome=" + outcome + "]";
		}

	}

	public record ProfileItem(ProfileKey key) implements Item {
	}

}
