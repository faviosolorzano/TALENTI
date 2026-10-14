package com.talenti.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.matching.domain.CompatibilityResult.Component;
import com.talenti.matching.domain.CompatibilityResult.ComponentKey;
import com.talenti.matching.domain.CompatibilityResult.ConditionItem;
import com.talenti.matching.domain.CompatibilityResult.Factor;
import com.talenti.matching.domain.CompatibilityResult.Level;
import com.talenti.matching.domain.CompatibilityResult.Outcome;
import com.talenti.matching.domain.CompatibilityResult.ProfileItem;
import com.talenti.matching.domain.CompatibilityResult.Reason;
import com.talenti.matching.domain.CompatibilityResult.RequirementItem;
import com.talenti.matching.domain.CompatibilityResult.Status;

/**
 * Prueba exhaustiva del motor de reglas (spec 007, sección 9: los 25 casos de la tabla y los criterios 007-01 a
 * 007-13). Todos los datos son inventados. Sin base de datos, sin Spring, sin IA.
 */
class CompatibilityCalculatorTests {

	private static final BigDecimal HALF = new BigDecimal("0.5");

	// ------------------------------------------------------------------------------------------ ayudantes

	private static MatchingParameters parameters(boolean similarity) {
		return new MatchingParameters(50, 15, 20, 15, 69, HALF, new BigDecimal("0.20"), new BigDecimal("0.60"), 50, 80,
				60, similarity);
	}

	private static CompatibilityCalculator calculator(boolean similarity) {
		return new CompatibilityCalculator(parameters(similarity));
	}

	/** Configuración actual: similitud apagada. */
	private static final CompatibilityCalculator OFF = calculator(false);

	private static final CompatibilityCalculator ON = calculator(true);

	private static VacancyFacts.Requirement required(String slug, int minLevel) {
		return new VacancyFacts.Requirement(slug, slug.toUpperCase(), RequirementKind.REQUIRED, minLevel);
	}

	private static VacancyFacts.Requirement desirable(String slug, int minLevel) {
		return new VacancyFacts.Requirement(slug, slug.toUpperCase(), RequirementKind.DESIRABLE, minLevel);
	}

	private static VacancyFacts remote(int hours, VacancyFacts.Requirement... requirements) {
		return new VacancyFacts(Modality.REMOTE, null, hours, List.of(requirements));
	}

	private static VacancyFacts hybrid(String city, int hours, VacancyFacts.Requirement... requirements) {
		return new VacancyFacts(Modality.HYBRID, city, hours, List.of(requirements));
	}

	private static Map<String, Integer> skills(Object... slugAndLevel) {
		Map<String, Integer> map = new LinkedHashMap<>();
		for (int i = 0; i < slugAndLevel.length; i += 2) {
			map.put((String) slugAndLevel[i], (Integer) slugAndLevel[i + 1]);
		}
		return map;
	}

	/** Perfil con condiciones perfectas para una vacante remota de 20 horas. */
	private static ProfileFacts profile(Map<String, Integer> skills) {
		return new ProfileFacts(skills, Set.of(Modality.REMOTE), "Lima", 20);
	}

	/** "Practicante Backend Java": los requisitos del ejemplo de RN-01. */
	private static VacancyFacts backendJava() {
		return remote(20, required("java", 2), required("spring-boot", 1), required("sql", 2), desirable("git", 1),
				desirable("docker", 1));
	}

	private static ProfileFacts exampleProfile() {
		return profile(skills("java", 2, "spring-boot", 1, "sql", 1, "git", 1));
	}

	private static BigDecimal score(CompatibilityResult result, ComponentKey key) {
		return result.components().stream().filter(c -> c.key() == key).findFirst().orElseThrow().score();
	}

	private static Component component(CompatibilityResult result, ComponentKey key) {
		return result.components().stream().filter(c -> c.key() == key).findFirst().orElseThrow();
	}

	private static void assertScore(CompatibilityResult result, ComponentKey key, String expected) {
		BigDecimal actual = score(result, key);
		assertThat(actual).as("puntaje de %s", key).isNotNull();
		assertThat(actual.subtract(new BigDecimal(expected)).abs()).as("%s = %s", actual, expected)
				.isLessThan(new BigDecimal("0.001"));
	}

	private static Outcome outcomeOf(CompatibilityResult result, Factor factor) {
		return java.util.stream.Stream.concat(result.matches().stream(),
				java.util.stream.Stream.concat(result.gaps().stream(), result.missingInfo().stream()))
				.filter(item -> item instanceof ConditionItem c && c.factor() == factor)
				.map(item -> ((ConditionItem) item).outcome()).findFirst().orElse(null);
	}

	// ------------------------------------------------------------------------------------------ 007-01, 007-02

	@Test
	@DisplayName("007-01 (caso 1): el ejemplo de RN-01 con coseno 0,55 da 82, con A=0,833 B=0,5 C=1 D=0,875")
	void t007_01_rn01ExampleWithSimilarity() {
		CompatibilityResult result = ON.calculate(exampleProfile(), backendJava(), 0.55);

		assertThat(result.status()).isEqualTo(Status.CALCULATED);
		assertThat(result.percent()).isEqualTo(82);
		assertScore(result, ComponentKey.REQUIRED, "0.833");
		assertScore(result, ComponentKey.DESIRABLE, "0.5");
		assertScore(result, ComponentKey.CONDITIONS, "1");
		assertScore(result, ComponentKey.SIMILARITY, "0.875");
		assertThat(result.informationBasis()).isEqualTo(100);
		assertThat(result.capped()).isFalse();
		assertThat(result.level()).isEqualTo(Level.HIGH);
		// Brecha del ejemplo: SQL en nivel menor al pedido; Docker, deseable, ausente.
		assertThat(result.gaps()).extracting(item -> ((RequirementItem) item).slug()).containsExactly("sql", "docker");
	}

	@Test
	@DisplayName("007-02 (caso 2): el mismo ejemplo con la similitud apagada da 81 y base 100; los pesos pasan a 50/15/20 sobre 85")
	void t007_02_rn01ExampleWithoutSimilarity() {
		CompatibilityResult result = OFF.calculate(exampleProfile(), backendJava(), null);

		assertThat(result.percent()).isEqualTo(81);
		assertThat(result.informationBasis()).isEqualTo(100);
		assertThat(component(result, ComponentKey.SIMILARITY).evaluable()).isFalse();
		// Entregar un coseno con la similitud apagada no cambia nada: el componente D no se evalúa.
		assertThat(OFF.calculate(exampleProfile(), backendJava(), 0.55)).isEqualTo(result);
		// 69,17 / 85 = 81,37
		assertThat(result.rawPercent().subtract(new BigDecimal("81.37")).abs()).isLessThan(new BigDecimal("0.01"));
	}

	@Test
	@DisplayName("007-02: con la similitud encendida pero sin coseno (aún sin vectores) el componente D no es evaluable y la base baja")
	void t007_02_similarityEnabledWithoutCosine() {
		CompatibilityResult result = ON.calculate(exampleProfile(), backendJava(), null);

		assertThat(component(result, ComponentKey.SIMILARITY).evaluable()).isFalse();
		assertThat(result.percent()).isEqualTo(81);
		assertThat(result.informationBasis()).isEqualTo(85);
	}

	// ------------------------------------------------------------------------------------------ casos 3 a 7

	@Test
	@DisplayName("007-01 (caso 3): todo cumplido da 100")
	void t007_01_everythingMet() {
		ProfileFacts profile = profile(skills("java", 3, "spring-boot", 3, "sql", 3, "git", 3, "docker", 3));

		CompatibilityResult result = OFF.calculate(profile, backendJava(), null);

		assertThat(result.percent()).isEqualTo(100);
		assertThat(result.capped()).isFalse();
		assertThat(result.gaps()).isEmpty();
		assertThat(result.missingInfo()).isEmpty();
		assertThat(result.level()).isEqualTo(Level.HIGH);
	}

	@Test
	@DisplayName("007-05 (caso 4): A=2/3, B=1, C=1 con un obligatorio ausente: bruto 80, tope 69 y capped verdadero")
	void t007_05_capLowersTheNumber() {
		VacancyFacts vacancy = remote(20, required("java", 1), required("sql", 1), required("react", 1),
				desirable("git", 1));
		ProfileFacts profile = profile(skills("java", 1, "sql", 1, "git", 1));

		CompatibilityResult result = OFF.calculate(profile, vacancy, null);

		assertScore(result, ComponentKey.REQUIRED, "0.667");
		assertThat(result.rawPercent().setScale(0, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("80");
		assertThat(result.percent()).isEqualTo(69);
		assertThat(result.capped()).isTrue();
		assertThat(result.gaps()).anySatisfy(item -> {
			RequirementItem requirement = (RequirementItem) item;
			assertThat(requirement.slug()).isEqualTo("react");
			assertThat(requirement.outcome()).isEqualTo(Outcome.MISSING);
		});
	}

	@Test
	@DisplayName("007-05 (caso 5): A=1/3, B=0, C=0 con obligatorios ausentes da 20 sin tope aplicado y con MISSING en gaps")
	void t007_05_missingRequiredButCapDidNotChangeTheNumber() {
		VacancyFacts vacancy = hybrid("Lima", 20, required("java", 1), required("sql", 1), required("react", 1),
				desirable("git", 1));
		ProfileFacts profile = new ProfileFacts(skills("java", 1), Set.of(Modality.ONSITE), "Cusco", 0);

		CompatibilityResult result = OFF.calculate(profile, vacancy, null);

		assertScore(result, ComponentKey.REQUIRED, "0.333");
		assertScore(result, ComponentKey.DESIRABLE, "0");
		assertScore(result, ComponentKey.CONDITIONS, "0");
		assertThat(result.percent()).isEqualTo(20);
		assertThat(result.capped()).isFalse();
		assertThat(result.gaps()).filteredOn(item -> item instanceof RequirementItem r && r.outcome() == Outcome.MISSING)
				.extracting(item -> ((RequirementItem) item).slug()).containsExactlyInAnyOrder("sql", "react", "git");
		assertThat(result.level()).isEqualTo(Level.LOW);
	}

	@Test
	@DisplayName("007-05 (caso 6): tres obligatorios un nivel por debajo, B=1 y C=1 dan 71 y un crédito parcial nunca activa el tope")
	void t007_05_partialCreditNeverCaps() {
		VacancyFacts vacancy = remote(20, required("java", 3), required("sql", 3), required("react", 3),
				desirable("git", 1));
		ProfileFacts profile = profile(skills("java", 2, "sql", 2, "react", 2, "git", 1));

		CompatibilityResult result = OFF.calculate(profile, vacancy, null);

		assertScore(result, ComponentKey.REQUIRED, "0.5");
		assertThat(result.percent()).isEqualTo(71);
		assertThat(result.capped()).isFalse();
		assertThat(result.gaps()).allSatisfy(item -> assertThat(((RequirementItem) item).outcome())
				.isEqualTo(Outcome.PARTIAL));
	}

	@Test
	@DisplayName("007-03 (caso 7): nivel 3 con mínimo 2 vale 1; 2 con 2 vale 1; 1 con 2 vale 0,5; ausente vale 0")
	void t007_03_creditPerRequirement() {
		VacancyFacts vacancy = remote(20, required("java", 2));

		assertScore(OFF.calculate(profile(skills("java", 3)), vacancy, null), ComponentKey.REQUIRED, "1");
		assertScore(OFF.calculate(profile(skills("java", 2)), vacancy, null), ComponentKey.REQUIRED, "1");
		assertScore(OFF.calculate(profile(skills("java", 1)), vacancy, null), ComponentKey.REQUIRED, "0.5");
		assertScore(OFF.calculate(profile(skills("sql", 3)), vacancy, null), ComponentKey.REQUIRED, "0");
	}

	@Test
	@DisplayName("007-03: sin requisitos deseables B no es evaluable y se excluye; sin obligatorios A no es evaluable")
	void t007_03_nonEvaluableComponents() {
		CompatibilityResult withoutDesirable = OFF.calculate(profile(skills("java", 2)),
				remote(20, required("java", 2)), null);
		CompatibilityResult withoutRequired = OFF.calculate(profile(skills("java", 2)),
				remote(20, desirable("java", 2)), null);

		assertThat(component(withoutDesirable, ComponentKey.DESIRABLE).evaluable()).isFalse();
		assertThat(component(withoutDesirable, ComponentKey.DESIRABLE).score()).isNull();
		assertThat(component(withoutRequired, ComponentKey.REQUIRED).evaluable()).isFalse();
	}

	// ------------------------------------------------------------------------------------------ casos 8 a 12

	@Test
	@DisplayName("007-06 (caso 8): sin habilidades y con condiciones perfectas es INSUFFICIENT con NO_SKILLS, sin porcentaje")
	void t007_06_noSkills() {
		ProfileFacts profile = new ProfileFacts(Map.of(), Set.of(Modality.REMOTE), "Lima", 40);

		CompatibilityResult result = OFF.calculate(profile, backendJava(), null);

		assertThat(result.status()).isEqualTo(Status.INSUFFICIENT);
		assertThat(result.reasons()).containsExactly(Reason.NO_SKILLS);
		assertThat(result.percent()).isNull();
		assertThat(result.level()).isNull();
		assertThat(result.informationBasis()).isNull();
		assertThat(result.capped()).isFalse();
		assertThat(result.missingInfo()).containsExactly(new ProfileItem(CompatibilityResult.ProfileKey.SKILLS));
	}

	@Test
	@DisplayName("007-06 (caso 9): solo A evaluable (peso 50) con A=0,833 da 83 y base 59; con exactamente 50 se calcula")
	void t007_06_exactlyTheMinimumWeightCalculates() {
		ProfileFacts profile = new ProfileFacts(skills("java", 2, "spring-boot", 1, "sql", 1), Set.of(), null, null);
		VacancyFacts vacancy = remote(20, required("java", 2), required("spring-boot", 1), required("sql", 2));

		CompatibilityResult result = OFF.calculate(profile, vacancy, null);

		assertScore(result, ComponentKey.REQUIRED, "0.833");
		assertThat(result.status()).isEqualTo(Status.CALCULATED);
		assertThat(result.percent()).isEqualTo(83);
		assertThat(result.informationBasis()).isEqualTo(59);
		assertThat(component(result, ComponentKey.CONDITIONS).evaluable()).isFalse();
		assertThat(result.missingInfo()).extracting(item -> ((ConditionItem) item).factor())
				.containsExactlyInAnyOrder(Factor.MODALITY, Factor.AVAILABILITY);
	}

	@Test
	@DisplayName("007-06 (caso 10): una vacante sin obligatorios, con deseables y condiciones (35 de peso), es INSUFFICIENT con LOW_INFORMATION")
	void t007_06_belowTheMinimumWeight() {
		CompatibilityResult result = OFF.calculate(profile(skills("git", 1)), remote(20, desirable("git", 1)), null);

		assertThat(result.status()).isEqualTo(Status.INSUFFICIENT);
		assertThat(result.reasons()).containsExactly(Reason.LOW_INFORMATION);
		assertThat(result.percent()).isNull();
	}

	@Test
	@DisplayName("007-06 (caso 11): una vacante sin ningún requisito es INSUFFICIENT con VACANCY_WITHOUT_REQUIREMENTS")
	void t007_06_vacancyWithoutRequirements() {
		CompatibilityResult result = OFF.calculate(profile(skills("git", 1)), remote(20), null);

		assertThat(result.status()).isEqualTo(Status.INSUFFICIENT);
		assertThat(result.reasons()).containsExactly(Reason.VACANCY_WITHOUT_REQUIREMENTS);
		assertThat(result.percent()).isNull();
	}

	@Test
	@DisplayName("007-06 (caso 12): sin deseables en la vacante, con A=1 y C=1 da 100 (pesos 50/20 sobre 70)")
	void t007_06_withoutDesirables() {
		CompatibilityResult result = OFF.calculate(profile(skills("java", 2)), remote(20, required("java", 2)), null);

		assertThat(result.percent()).isEqualTo(100);
		assertThat(result.informationBasis()).isEqualTo(82);
	}

	// ------------------------------------------------------------------------------------------ casos 13 a 16

	@Test
	@DisplayName("007-04 (caso 13): vacante remota, modalidad 1 y 10 de 20 horas dan C=0,75 y sin factor de ubicación")
	void t007_04_remoteHasNoLocationFactor() {
		ProfileFacts profile = new ProfileFacts(skills("java", 2), Set.of(Modality.REMOTE), "Cusco", 10);

		CompatibilityResult result = OFF.calculate(profile, remote(20, required("java", 2)), null);

		assertScore(result, ComponentKey.CONDITIONS, "0.75");
		assertThat(outcomeOf(result, Factor.LOCATION)).as("ubicación en remoto no aparece").isNull();
		assertThat(outcomeOf(result, Factor.MODALITY)).isEqualTo(Outcome.MATCH);
		assertThat(outcomeOf(result, Factor.AVAILABILITY)).isEqualTo(Outcome.PARTIAL);
	}

	@Test
	@DisplayName("007-04 (caso 14): híbrida con ciudad distinta vale 0; '  LIMA ' y 'Lima' valen 1; 'Arequípa' y 'Arequipa' valen 1")
	void t007_04_locationMatching() {
		VacancyFacts lima = hybrid("Lima", 20, required("java", 2));
		VacancyFacts arequipa = hybrid("Arequipa", 20, required("java", 2));

		CompatibilityResult different = OFF.calculate(withCity("Cusco"), lima, null);
		CompatibilityResult spaces = OFF.calculate(withCity("  LIMA "), lima, null);
		CompatibilityResult accents = OFF.calculate(withCity("Arequípa"), arequipa, null);
		CompatibilityResult accentsInVacancy = OFF.calculate(withCity("Arequipa"),
				hybrid("Arequípa", 20, required("java", 2)), null);

		assertThat(outcomeOf(different, Factor.LOCATION)).isEqualTo(Outcome.MISMATCH);
		assertThat(outcomeOf(spaces, Factor.LOCATION)).isEqualTo(Outcome.MATCH);
		assertThat(outcomeOf(accents, Factor.LOCATION)).isEqualTo(Outcome.MATCH);
		assertThat(outcomeOf(accentsInVacancy, Factor.LOCATION)).isEqualTo(Outcome.MATCH);
	}

	private static ProfileFacts withCity(String city) {
		return new ProfileFacts(skills("java", 2), Set.of(Modality.HYBRID), city, 20);
	}

	@Test
	@DisplayName("007-04 (caso 15): 30 de 20 horas vale 1; 10 de 20 vale 0,5; 1 de 20 vale 0,05")
	void t007_04_availabilityIsAProportionCappedAtOne() {
		// Solo la disponibilidad es evaluable (sin modalidades declaradas y vacante remota): C = su valor.
		VacancyFacts vacancy = remote(20, required("java", 2));

		assertScore(OFF.calculate(new ProfileFacts(skills("java", 2), Set.of(), null, 30), vacancy, null),
				ComponentKey.CONDITIONS, "1");
		assertScore(OFF.calculate(new ProfileFacts(skills("java", 2), Set.of(), null, 10), vacancy, null),
				ComponentKey.CONDITIONS, "0.5");
		assertScore(OFF.calculate(new ProfileFacts(skills("java", 2), Set.of(), null, 1), vacancy, null),
				ComponentKey.CONDITIONS, "0.05");
	}

	@Test
	@DisplayName("007-04 (caso 16): sin modalidades aceptadas el factor no es evaluable y se excluye (aparece como dato que falta)")
	void t007_04_emptyModalitiesAreNotEvaluable() {
		ProfileFacts profile = new ProfileFacts(skills("java", 2), Set.of(), "Lima", 20);

		CompatibilityResult result = OFF.calculate(profile, hybrid("Lima", 20, required("java", 2)), null);

		assertThat(outcomeOf(result, Factor.MODALITY)).isEqualTo(Outcome.UNKNOWN);
		assertThat(result.missingInfo()).hasSize(1);
		// C es el promedio de ubicación (1) y disponibilidad (1): la modalidad no resta.
		assertScore(result, ComponentKey.CONDITIONS, "1");
	}

	@Test
	@DisplayName("007-04: sin ninguna condición evaluable C no es evaluable; sin ubicación en el perfil el factor es UNKNOWN")
	void t007_04_noConditionIsEvaluable() {
		ProfileFacts profile = new ProfileFacts(skills("java", 2), Set.of(), null, null);

		CompatibilityResult result = OFF.calculate(profile, hybrid("Lima", 20, required("java", 2)), null);

		assertThat(component(result, ComponentKey.CONDITIONS).evaluable()).isFalse();
		assertThat(result.missingInfo()).extracting(item -> ((ConditionItem) item).factor())
				.containsExactlyInAnyOrder(Factor.MODALITY, Factor.LOCATION, Factor.AVAILABILITY);
	}

	@Test
	@DisplayName("007-04: una modalidad que el perfil no acepta vale 0 y queda en las brechas")
	void t007_04_modalityMismatch() {
		ProfileFacts profile = new ProfileFacts(skills("java", 2), Set.of(Modality.ONSITE), "Lima", 20);

		CompatibilityResult result = OFF.calculate(profile, remote(20, required("java", 2)), null);

		assertThat(outcomeOf(result, Factor.MODALITY)).isEqualTo(Outcome.MISMATCH);
		assertThat(result.gaps()).anySatisfy(item -> assertThat(item).isInstanceOfSatisfying(ConditionItem.class,
				c -> assertThat(c.vacancyValue()).isEqualTo("REMOTE")));
	}

	// ------------------------------------------------------------------------------------------ casos 17 y 18

	@Test
	@DisplayName("007-05 (caso 17): bruto 69,4 con obligatorio ausente da 69 sin tope aplicado; bruto 69,5 redondea a 70 y baja a 69 con capped")
	void t007_05_capBoundary() {
		CompatibilityCalculator.Finished below = OFF.finish(new BigDecimal("69.4"), true);
		CompatibilityCalculator.Finished at = OFF.finish(new BigDecimal("69.5"), true);
		CompatibilityCalculator.Finished above = OFF.finish(new BigDecimal("95.2"), true);

		assertThat(below).isEqualTo(new CompatibilityCalculator.Finished(69, false));
		assertThat(at).isEqualTo(new CompatibilityCalculator.Finished(69, true));
		assertThat(above).isEqualTo(new CompatibilityCalculator.Finished(69, true));
		// Sin obligatorio ausente no hay tope.
		assertThat(OFF.finish(new BigDecimal("95.2"), false)).isEqualTo(new CompatibilityCalculator.Finished(95, false));
	}

	@Test
	@DisplayName("007-07 (caso 18): 82,49 redondea a 82; 82,5 a 83; 82,51 a 83")
	void t007_07_roundingHalfUp() {
		assertThat(OFF.finish(new BigDecimal("82.49"), false).percent()).isEqualTo(82);
		assertThat(OFF.finish(new BigDecimal("82.5"), false).percent()).isEqualTo(83);
		assertThat(OFF.finish(new BigDecimal("82.51"), false).percent()).isEqualTo(83);
	}

	@Test
	@DisplayName("007-07: el porcentaje siempre queda entre 0 y 100, también con brutos fuera de rango")
	void t007_07_percentIsAlwaysBetween0And100() {
		assertThat(OFF.finish(new BigDecimal("-3.2"), false).percent()).isZero();
		assertThat(OFF.finish(new BigDecimal("104.7"), false).percent()).isEqualTo(100);
	}

	// ------------------------------------------------------------------------------------------ casos 19 a 22

	@Test
	@DisplayName("007-07 (caso 19): los requisitos en otro orden y 100 repeticiones dan un resultado idéntico")
	void t007_07_deterministicAndOrderIndependent() {
		List<VacancyFacts.Requirement> requirements = new ArrayList<>(backendJava().requirements());
		VacancyFacts original = new VacancyFacts(Modality.REMOTE, null, 20, requirements);
		CompatibilityResult expected = ON.calculate(exampleProfile(), original, 0.55);

		for (int i = 0; i < 100; i++) {
			List<VacancyFacts.Requirement> shuffled = new ArrayList<>(requirements);
			Collections.shuffle(shuffled, new java.util.Random(i));
			VacancyFacts vacancy = new VacancyFacts(Modality.REMOTE, null, 20, shuffled);

			assertThat(ON.calculate(exampleProfile(), vacancy, 0.55)).isEqualTo(expected);
		}
	}

	@Test
	@DisplayName("007-09 (caso 20): pesos 40/20/20/20 cambian el resultado; pesos que no suman 100 hacen fallar la creación de la fórmula")
	void t007_09_weightsAreParameters() {
		MatchingParameters other = new MatchingParameters(40, 20, 20, 20, 69, HALF, new BigDecimal("0.20"),
				new BigDecimal("0.60"), 50, 80, 60, false);

		CompatibilityResult changed = new CompatibilityCalculator(other).calculate(exampleProfile(), backendJava(),
				null);
		CompatibilityResult original = OFF.calculate(exampleProfile(), backendJava(), null);

		// (0,833*40 + 0,5*20 + 20) / 80 = 79,17 -> 79
		assertThat(changed.percent()).isEqualTo(79).isNotEqualTo(original.percent());
		assertThatThrownBy(() -> new MatchingParameters(50, 15, 20, 10, 69, HALF, new BigDecimal("0.20"),
				new BigDecimal("0.60"), 50, 80, 60, false)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("sumar 100");
		assertThatThrownBy(() -> new MatchingParameters(60, 15, 20, 15, 69, HALF, new BigDecimal("0.20"),
				new BigDecimal("0.60"), 50, 80, 60, false)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("007-09: el tope, el crédito parcial y el mínimo de peso también son parámetros")
	void t007_09_otherParametersMatter() {
		VacancyFacts vacancy = remote(20, required("java", 1), required("sql", 1), required("react", 1),
				desirable("git", 1));
		ProfileFacts profile = profile(skills("java", 1, "sql", 1, "git", 1));
		MatchingParameters capped = new MatchingParameters(50, 15, 20, 15, 50, HALF, new BigDecimal("0.20"),
				new BigDecimal("0.60"), 50, 80, 60, false);
		MatchingParameters strict = new MatchingParameters(50, 15, 20, 15, 69, HALF, new BigDecimal("0.20"),
				new BigDecimal("0.60"), 60, 80, 60, false);

		assertThat(new CompatibilityCalculator(capped).calculate(profile, vacancy, null).percent()).isEqualTo(50);
		// Con mínimo 60, solo A+B+C... (peso 85) alcanza; sin condiciones (50+15=65) también; sin deseables (50) no.
		assertThat(new CompatibilityCalculator(strict)
				.calculate(new ProfileFacts(skills("java", 1), Set.of(), null, null), remote(20, required("java", 1)),
						null)
				.status()).isEqualTo(Status.INSUFFICIENT);
	}

	@Test
	@DisplayName("007-08 (caso 21): coseno 0,10; 0,20; 0,40; 0,60; 0,90; -0,3 dan 0; 0; 0,5; 1; 1; 0 y sin coseno no es evaluable")
	void t007_08_cosineToScore() {
		assertThat(ON.similarityScore(0.10)).isEqualByComparingTo("0");
		assertThat(ON.similarityScore(0.20)).isEqualByComparingTo("0");
		assertThat(ON.similarityScore(0.40)).isEqualByComparingTo("0.5");
		assertThat(ON.similarityScore(0.60)).isEqualByComparingTo("1");
		assertThat(ON.similarityScore(0.90)).isEqualByComparingTo("1");
		assertThat(ON.similarityScore(-0.3)).isEqualByComparingTo("0");
		assertThat(ON.similarityScore(0.55)).isEqualByComparingTo("0.875");
		assertThat(ON.similarityScore(null)).isNull();
		assertThat(ON.similarityScore(Double.NaN)).isNull();
	}

	@Test
	@DisplayName("007-03 (caso 22): una habilidad del perfil que la vacante no pide no suma ni resta")
	void t007_03_extraSkillsAreIgnored() {
		ProfileFacts base = exampleProfile();
		Map<String, Integer> more = new LinkedHashMap<>(base.skillLevels());
		more.put("kotlin", 3);
		more.put("react", 3);

		CompatibilityResult without = OFF.calculate(base, backendJava(), null);
		CompatibilityResult with = OFF.calculate(new ProfileFacts(more, base.acceptedModalities(), base.location(),
				base.hoursPerWeek()), backendJava(), null);

		assertThat(with).isEqualTo(without);
	}

	// ------------------------------------------------------------------------------------------ casos 23 y 25

	@Test
	@DisplayName("007-11 (caso 23): 59 es LOW; 60 es MEDIUM; 79 es MEDIUM; 80 es HIGH")
	void t007_11_verdictThresholds() {
		assertThat(OFF.level(0)).isEqualTo(Level.LOW);
		assertThat(OFF.level(59)).isEqualTo(Level.LOW);
		assertThat(OFF.level(60)).isEqualTo(Level.MEDIUM);
		assertThat(OFF.level(79)).isEqualTo(Level.MEDIUM);
		assertThat(OFF.level(80)).isEqualTo(Level.HIGH);
		assertThat(OFF.level(100)).isEqualTo(Level.HIGH);
	}

	@Test
	@DisplayName("007-11: los umbrales del veredicto son parámetros")
	void t007_11_verdictThresholdsAreParameters() {
		CompatibilityCalculator other = new CompatibilityCalculator(new MatchingParameters(50, 15, 20, 15, 69, HALF,
				new BigDecimal("0.20"), new BigDecimal("0.60"), 50, 90, 70, false));

		assertThat(other.level(89)).isEqualTo(Level.MEDIUM);
		assertThat(other.level(90)).isEqualTo(Level.HIGH);
		assertThat(other.level(69)).isEqualTo(Level.LOW);
	}

	@Test
	@DisplayName("007-07 (caso 25): con todo en cero da 0, nunca negativo, y no activa el tope")
	void t007_07_everythingZero() {
		ProfileFacts profile = new ProfileFacts(skills("kotlin", 1), Set.of(Modality.ONSITE), "Cusco", 0);
		VacancyFacts vacancy = remote(20, required("java", 2), required("sql", 2), desirable("git", 1));

		CompatibilityResult result = OFF.calculate(profile, vacancy, null);

		assertThat(result.percent()).isZero();
		assertThat(result.capped()).isFalse();
		assertThat(result.level()).isEqualTo(Level.LOW);
	}

	// ------------------------------------------------------------------------------------------ 007-12, 007-10

	@Test
	@DisplayName("007-12: matches, gaps y missingInfo traen la evidencia de cada requisito y de cada condición")
	void t007_12_listsCarryEvidence() {
		ProfileFacts profile = new ProfileFacts(skills("java", 2, "sql", 1, "git", 1), Set.of(Modality.REMOTE), null,
				null);

		CompatibilityResult result = OFF.calculate(profile, backendJava(), null);

		assertThat(result.matches()).filteredOn(item -> item instanceof RequirementItem)
				.containsExactly(new RequirementItem("java", "JAVA", RequirementKind.REQUIRED, 2, 2, Outcome.MATCH),
						new RequirementItem("git", "GIT", RequirementKind.DESIRABLE, 1, 1, Outcome.MATCH));
		assertThat(result.gaps()).filteredOn(item -> item instanceof RequirementItem)
				.containsExactlyInAnyOrder(
						new RequirementItem("sql", "SQL", RequirementKind.REQUIRED, 2, 1, Outcome.PARTIAL),
						new RequirementItem("spring-boot", "SPRING-BOOT", RequirementKind.REQUIRED, 1, null,
								Outcome.MISSING),
						new RequirementItem("docker", "DOCKER", RequirementKind.DESIRABLE, 1, null, Outcome.MISSING));
		assertThat(result.matches()).contains(new ConditionItem(Factor.MODALITY, Outcome.MATCH, "REMOTE", "REMOTE"));
		assertThat(result.missingInfo()).containsExactly(new ConditionItem(Factor.AVAILABILITY, Outcome.UNKNOWN, "20", null));
	}

	@Test
	@DisplayName("007-12: las listas llevan primero los obligatorios, luego los deseables, siempre en el mismo orden")
	void t007_12_listOrderIsStable() {
		CompatibilityResult result = OFF.calculate(profile(skills("kotlin", 1)), backendJava(), null);

		assertThat(result.gaps()).filteredOn(item -> item instanceof RequirementItem)
				.extracting(item -> ((RequirementItem) item).slug())
				.containsExactly("java", "sql", "spring-boot", "docker", "git");
	}

	@Test
	@DisplayName("007-10: la entrada del cálculo solo tiene habilidades, modalidades, ubicación y horas; ni un dato personal")
	void t007_10_inputHasNoPersonalFields() {
		List<String> names = new ArrayList<>();
		for (var component : ProfileFacts.class.getRecordComponents()) {
			names.add(component.getName());
		}
		for (var component : VacancyFacts.class.getRecordComponents()) {
			names.add(component.getName());
		}

		assertThat(names).containsExactlyInAnyOrder("skillLevels", "acceptedModalities", "location", "hoursPerWeek",
				"modality", "location", "hoursPerWeek", "requirements");
		assertThat(new ProfileFacts(skills("java", 2), Set.of(), "Lima", 5).toString()).doesNotContain("Lima")
				.doesNotContain("java");
	}

}
