package com.talenti.matching.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.talenti.matching.domain.CompatibilityResult.Component;
import com.talenti.matching.domain.CompatibilityResult.ComponentKey;
import com.talenti.matching.domain.CompatibilityResult.ConditionItem;
import com.talenti.matching.domain.CompatibilityResult.Factor;
import com.talenti.matching.domain.CompatibilityResult.Item;
import com.talenti.matching.domain.CompatibilityResult.Level;
import com.talenti.matching.domain.CompatibilityResult.Outcome;
import com.talenti.matching.domain.CompatibilityResult.ProfileItem;
import com.talenti.matching.domain.CompatibilityResult.ProfileKey;
import com.talenti.matching.domain.CompatibilityResult.Reason;
import com.talenti.matching.domain.CompatibilityResult.RequirementItem;
import com.talenti.matching.domain.CompatibilityResult.Status;

/**
 * La fórmula de compatibilidad de RN-01 como función pura (007-01 a 007-13): sin base de datos, sin IA, sin reloj y
 * sin estado. Con los mismos datos y los mismos parámetros da siempre el mismo resultado, también si los requisitos
 * llegan en otro orden (RN-05). Usa aritmética decimal y redondea el entero hacia arriba desde 0,5.
 *
 * <p>Este paquete no conoce a ningún otro módulo ni a ningún proveedor de IA: la similitud de proyectos (S12) entra
 * como un coseno ya calculado, y es opcional.
 */
public final class CompatibilityCalculator {

	/** Se sube al cambiar la fórmula o sus parámetros (RN-05, 007-09). */
	public static final String FORMULA_VERSION = "1";

	private static final MathContext MC = new MathContext(30, RoundingMode.HALF_UP);

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private final MatchingParameters parameters;

	public CompatibilityCalculator(MatchingParameters parameters) {
		this.parameters = parameters;
	}

	public MatchingParameters parameters() {
		return parameters;
	}

	/**
	 * @param cosine similitud coseno entre los proyectos y la vacante; nula si no hay (o la similitud está apagada)
	 */
	public CompatibilityResult calculate(ProfileFacts profile, VacancyFacts vacancy, Double cosine) {
		if (profile.skillLevels().isEmpty()) {
			return noSkills();
		}

		List<VacancyFacts.Requirement> ordered = vacancy.requirements().stream()
				.sorted(Comparator.comparing(VacancyFacts.Requirement::kind)
						.thenComparing(Comparator.comparingInt(VacancyFacts.Requirement::minLevel).reversed())
						.thenComparing(VacancyFacts.Requirement::slug))
				.toList();

		List<Item> matches = new ArrayList<>();
		List<Item> gaps = new ArrayList<>();
		List<Item> missingInfo = new ArrayList<>();

		List<BigDecimal> requiredCredits = new ArrayList<>();
		List<BigDecimal> desirableCredits = new ArrayList<>();
		boolean requiredMissing = false;
		for (VacancyFacts.Requirement requirement : ordered) {
			Integer level = profile.skillLevels().get(requirement.slug());
			BigDecimal credit;
			Outcome outcome;
			if (level == null) {
				credit = BigDecimal.ZERO;
				outcome = Outcome.MISSING;
			}
			else if (level >= requirement.minLevel()) {
				credit = BigDecimal.ONE;
				outcome = Outcome.MATCH;
			}
			else {
				credit = parameters.partialCredit();
				outcome = Outcome.PARTIAL;
			}
			if (requirement.kind() == RequirementKind.REQUIRED) {
				requiredCredits.add(credit);
				requiredMissing |= outcome == Outcome.MISSING;
			}
			else {
				desirableCredits.add(credit);
			}
			(outcome == Outcome.MATCH ? matches : gaps).add(new RequirementItem(requirement.slug(),
					requirement.name(), requirement.kind(), requirement.minLevel(), level, outcome));
		}

		List<BigDecimal> conditionCredits = new ArrayList<>();
		conditions(profile, vacancy, conditionCredits, matches, gaps, missingInfo);

		BigDecimal scoreRequired = average(requiredCredits);
		BigDecimal scoreDesirable = average(desirableCredits);
		BigDecimal scoreConditions = average(conditionCredits);
		BigDecimal scoreSimilarity = parameters.similarityEnabled() ? similarityScore(cosine) : null;

		List<Component> components = List.of(
				new Component(ComponentKey.REQUIRED, parameters.weightRequired(), scoreRequired, scoreRequired != null),
				new Component(ComponentKey.DESIRABLE, parameters.weightDesirable(), scoreDesirable,
						scoreDesirable != null),
				new Component(ComponentKey.CONDITIONS, parameters.weightConditions(), scoreConditions,
						scoreConditions != null),
				new Component(ComponentKey.SIMILARITY, parameters.weightSimilarity(), scoreSimilarity,
						scoreSimilarity != null));

		BigDecimal evaluableWeight = BigDecimal.ZERO;
		BigDecimal weighted = BigDecimal.ZERO;
		for (Component component : components) {
			if (component.evaluable()) {
				BigDecimal weight = BigDecimal.valueOf(component.weight());
				evaluableWeight = evaluableWeight.add(weight);
				weighted = weighted.add(weight.multiply(component.score(), MC), MC);
			}
		}
		int activeWeight = parameters.weightRequired() + parameters.weightDesirable() + parameters.weightConditions()
				+ (parameters.similarityEnabled() ? parameters.weightSimilarity() : 0);

		List<Reason> reasons = new ArrayList<>();
		if (vacancy.requirements().isEmpty()) {
			reasons.add(Reason.VACANCY_WITHOUT_REQUIREMENTS);
		}
		else if (evaluableWeight.compareTo(BigDecimal.valueOf(parameters.minEvaluableWeight())) < 0) {
			reasons.add(Reason.LOW_INFORMATION);
		}
		if (!reasons.isEmpty()) {
			return new CompatibilityResult(Status.INSUFFICIENT, reasons, null, null, null, false, null, components,
					matches, gaps, missingInfo);
		}

		BigDecimal raw = weighted.divide(evaluableWeight, MC).multiply(HUNDRED, MC);
		Finished finished = finish(raw, requiredMissing);
		int basis = evaluableWeight.multiply(HUNDRED, MC)
				.divide(BigDecimal.valueOf(activeWeight), MC).setScale(0, RoundingMode.HALF_UP).intValueExact();
		return new CompatibilityResult(Status.CALCULATED, List.of(), finished.percent(), raw,
				level(finished.percent()), finished.capped(), basis, components, matches, gaps, missingInfo);
	}

	/** Resultado de redondear y aplicar el tope. */
	public record Finished(int percent, boolean capped) {
	}

	/**
	 * Redondea al entero (hacia arriba desde 0,5) y aplica el tope si falta un obligatorio. {@code capped} es
	 * verdadero solo si el tope bajó la cifra (007-05). El resultado queda siempre entre 0 y 100 (007-07).
	 */
	public Finished finish(BigDecimal rawPercent, boolean requiredMissing) {
		int rounded = rawPercent.setScale(0, RoundingMode.HALF_UP).intValueExact();
		rounded = Math.max(0, Math.min(100, rounded));
		if (requiredMissing && rounded > parameters.cap()) {
			return new Finished(parameters.cap(), true);
		}
		return new Finished(rounded, false);
	}

	/** Veredicto de un porcentaje (007-11). */
	public Level level(int percent) {
		if (percent >= parameters.verdictHigh()) {
			return Level.HIGH;
		}
		return percent >= parameters.verdictMedium() ? Level.MEDIUM : Level.LOW;
	}

	/**
	 * De coseno a puntaje: {@code (coseno - bajo) / (alto - bajo)} acotado entre 0 y 1 (007-08). Nulo si no hay
	 * coseno (el componente no es evaluable).
	 */
	public BigDecimal similarityScore(Double cosine) {
		if (cosine == null || cosine.isNaN() || cosine.isInfinite()) {
			return null;
		}
		BigDecimal low = parameters.cosineLow();
		BigDecimal score = BigDecimal.valueOf(cosine).subtract(low, MC)
				.divide(parameters.cosineHigh().subtract(low, MC), MC);
		return score.max(BigDecimal.ZERO).min(BigDecimal.ONE);
	}

	// ----------------------------------------------------------------------------------------------- internos

	private CompatibilityResult noSkills() {
		List<Component> components = List.of(
				new Component(ComponentKey.REQUIRED, parameters.weightRequired(), null, false),
				new Component(ComponentKey.DESIRABLE, parameters.weightDesirable(), null, false),
				new Component(ComponentKey.CONDITIONS, parameters.weightConditions(), null, false),
				new Component(ComponentKey.SIMILARITY, parameters.weightSimilarity(), null, false));
		return new CompatibilityResult(Status.INSUFFICIENT, List.of(Reason.NO_SKILLS), null, null, null, false, null,
				components, List.of(), List.of(), List.of(new ProfileItem(ProfileKey.SKILLS)));
	}

	private void conditions(ProfileFacts profile, VacancyFacts vacancy, List<BigDecimal> credits, List<Item> matches,
			List<Item> gaps, List<Item> missingInfo) {
		// Modalidad
		String vacancyModality = vacancy.modality().name();
		if (profile.acceptedModalities().isEmpty()) {
			missingInfo.add(new ConditionItem(Factor.MODALITY, Outcome.UNKNOWN, vacancyModality, null));
		}
		else {
			boolean accepted = profile.acceptedModalities().contains(vacancy.modality());
			credits.add(accepted ? BigDecimal.ONE : BigDecimal.ZERO);
			(accepted ? matches : gaps).add(new ConditionItem(Factor.MODALITY,
					accepted ? Outcome.MATCH : Outcome.MISMATCH, vacancyModality,
					acceptedList(profile.acceptedModalities())));
		}

		// Ubicación: no aplica en remoto (ni aparece), ni si la vacante no trae ciudad
		if (vacancy.modality() != Modality.REMOTE && vacancy.location() != null && !vacancy.location().isBlank()) {
			if (profile.location() == null || profile.location().isBlank()) {
				missingInfo.add(new ConditionItem(Factor.LOCATION, Outcome.UNKNOWN, vacancy.location().strip(), null));
			}
			else {
				boolean same = normalizeCity(profile.location()).equals(normalizeCity(vacancy.location()));
				credits.add(same ? BigDecimal.ONE : BigDecimal.ZERO);
				(same ? matches : gaps).add(new ConditionItem(Factor.LOCATION,
						same ? Outcome.MATCH : Outcome.MISMATCH, vacancy.location().strip(),
						profile.location().strip()));
			}
		}

		// Disponibilidad: min(1, horas del perfil / horas de la vacante)
		if (vacancy.hoursPerWeek() > 0) {
			String vacancyHours = Integer.toString(vacancy.hoursPerWeek());
			if (profile.hoursPerWeek() == null) {
				missingInfo.add(new ConditionItem(Factor.AVAILABILITY, Outcome.UNKNOWN, vacancyHours, null));
			}
			else {
				BigDecimal credit = BigDecimal.valueOf(Math.max(0, profile.hoursPerWeek()))
						.divide(BigDecimal.valueOf(vacancy.hoursPerWeek()), MC).min(BigDecimal.ONE);
				credits.add(credit);
				Outcome outcome = credit.compareTo(BigDecimal.ONE) >= 0 ? Outcome.MATCH
						: credit.signum() > 0 ? Outcome.PARTIAL : Outcome.MISMATCH;
				(outcome == Outcome.MATCH ? matches : gaps).add(new ConditionItem(Factor.AVAILABILITY, outcome,
						vacancyHours, Integer.toString(profile.hoursPerWeek())));
			}
		}
	}

	private static String acceptedList(Set<Modality> accepted) {
		List<String> names = new ArrayList<>();
		for (Modality modality : EnumSet.copyOf(accepted)) {
			names.add(modality.name());
		}
		return String.join(",", names);
	}

	/** Sin mayúsculas, tildes ni espacios sobrantes (007-04). */
	static String normalizeCity(String city) {
		String decomposed = Normalizer.normalize(city.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
		return decomposed.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
	}

	private static BigDecimal average(List<BigDecimal> values) {
		if (values.isEmpty()) {
			return null;
		}
		BigDecimal sum = BigDecimal.ZERO;
		for (BigDecimal value : values) {
			sum = sum.add(value, MC);
		}
		return sum.divide(BigDecimal.valueOf(values.size()), MC);
	}

}
