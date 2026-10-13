package com.talenti.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.profile.CompletenessItem;
import com.talenti.profile.ProfileCompleteness;

/** Regla de avance del perfil (RN-11): 006-09 (porcentaje, todo o nada), 006-10 (siguiente paso) y 006-24 (ABOUT). */
class CompletenessRuleTests {

	/** Los pesos decididos por Favio: 15, 25, 15, 25, 10 y 10; al menos 3 habilidades y 1 modalidad. */
	private final CompletenessRule rule = new CompletenessRule(15, 25, 15, 25, 10, 10, 3, 1);

	private static final ProfileFacts ACADEMIC = new ProfileFacts(true, true, true, 0, false, 0, 0, false, false);

	private static final ProfileFacts SKILLS = new ProfileFacts(false, false, false, 3, false, 0, 0, false, false);

	private static final ProfileFacts AVAILABILITY = new ProfileFacts(false, false, false, 0, true, 1, 0, false, false);

	private static final ProfileFacts PROJECTS = new ProfileFacts(false, false, false, 0, false, 0, 1, false, false);

	private static final ProfileFacts LOCATION = new ProfileFacts(false, false, false, 0, false, 0, 0, true, false);

	private static final ProfileFacts ABOUT = new ProfileFacts(false, false, false, 0, false, 0, 0, false, true);

	private static final ProfileFacts FULL = new ProfileFacts(true, true, true, 3, true, 1, 1, true, true);

	@Test
	@DisplayName("006-24: los pesos por defecto suman 100")
	void t006_24_defaultWeightsSumToOneHundred() {
		ProfileCompleteness result = rule.evaluate(ProfileFacts.empty());

		assertThat(result.items().stream().mapToInt(ProfileCompleteness.Item::weight).sum()).isEqualTo(100);
		assertThat(rule.evaluate(FULL).percent()).isEqualTo(100);
	}

	@Test
	@DisplayName("006-09: un perfil vacío tiene 0 % y los seis ítems pendientes, en el orden de la spec (ABOUT al final)")
	void t006_09_emptyProfileIsZeroPercent() {
		ProfileCompleteness result = rule.evaluate(ProfileFacts.empty());

		assertThat(result.percent()).isZero();
		assertThat(result.items()).extracting(ProfileCompleteness.Item::key).containsExactly(CompletenessItem.ACADEMIC,
				CompletenessItem.SKILLS, CompletenessItem.AVAILABILITY, CompletenessItem.PROJECTS,
				CompletenessItem.LOCATION, CompletenessItem.ABOUT);
		assertThat(result.items()).extracting(ProfileCompleteness.Item::weight).containsExactly(15, 25, 15, 25, 10, 10);
		assertThat(result.items()).allMatch(item -> !item.done());
	}

	@Test
	@DisplayName("006-09: cada ítem cumplido suma exactamente su peso")
	void t006_09_eachItemAddsItsWeight() {
		assertThat(rule.evaluate(ACADEMIC).percent()).isEqualTo(15);
		assertThat(rule.evaluate(SKILLS).percent()).isEqualTo(25);
		assertThat(rule.evaluate(AVAILABILITY).percent()).isEqualTo(15);
		assertThat(rule.evaluate(PROJECTS).percent()).isEqualTo(25);
		assertThat(rule.evaluate(LOCATION).percent()).isEqualTo(10);
		assertThat(rule.evaluate(ABOUT).percent()).isEqualTo(10);
	}

	@Test
	@DisplayName("006-09: los datos académicos son todo o nada: con 2 de 3 campos no suma")
	void t006_09_academicNeedsAllThreeFields() {
		assertThat(rule.evaluate(new ProfileFacts(true, true, false, 0, false, 0, 0, false, false)).percent()).isZero();
		assertThat(rule.evaluate(new ProfileFacts(true, false, true, 0, false, 0, 0, false, false)).percent()).isZero();
		assertThat(rule.evaluate(new ProfileFacts(false, true, true, 0, false, 0, 0, false, false)).percent()).isZero();
	}

	@Test
	@DisplayName("006-09: las habilidades son todo o nada: 2 no suman, 3 o más suman los 25 completos")
	void t006_09_skillsNeedThreeAndGiveNoPartialCredit() {
		assertThat(rule.evaluate(new ProfileFacts(false, false, false, 2, false, 0, 0, false, false)).percent()).isZero();
		assertThat(rule.evaluate(new ProfileFacts(false, false, false, 9, false, 0, 0, false, false)).percent())
				.isEqualTo(25);
	}

	@Test
	@DisplayName("006-09: la disponibilidad pide horas Y al menos una modalidad; una sola cosa no suma")
	void t006_09_availabilityNeedsHoursAndModality() {
		assertThat(rule.evaluate(new ProfileFacts(false, false, false, 0, true, 0, 0, false, false)).percent()).isZero();
		assertThat(rule.evaluate(new ProfileFacts(false, false, false, 0, false, 2, 0, false, false)).percent()).isZero();
		assertThat(rule.evaluate(AVAILABILITY).percent()).isEqualTo(15);
	}

	@Test
	@DisplayName("006-09: para las 64 combinaciones, el porcentaje es la suma de los pesos de lo cumplido")
	void t006_09_everyCombinationSumsItsWeights() {
		int[] weights = { 15, 25, 15, 25, 10, 10 };
		for (int mask = 0; mask < 64; mask++) {
			boolean academic = (mask & 1) != 0;
			boolean skills = (mask & 2) != 0;
			boolean availability = (mask & 4) != 0;
			boolean projects = (mask & 8) != 0;
			boolean location = (mask & 16) != 0;
			boolean about = (mask & 32) != 0;
			ProfileFacts facts = new ProfileFacts(academic, academic, academic, skills ? 3 : 0, availability,
					availability ? 1 : 0, projects ? 1 : 0, location, about);
			int expected = 0;
			for (int i = 0; i < 6; i++) {
				if ((mask & (1 << i)) != 0) {
					expected += weights[i];
				}
			}

			assertThat(rule.evaluate(facts).percent()).as("combinación %d", mask).isEqualTo(expected);
		}
	}

	@Test
	@DisplayName("006-10: el siguiente paso es el pendiente de mayor peso; a igualdad, el primero del orden; ABOUT al final")
	void t006_10_nextStepIsHeaviestPendingWithTieBreak() {
		// Vacío: SKILLS y PROJECTS empatan en 25; gana SKILLS.
		assertThat(rule.evaluate(ProfileFacts.empty()).nextStep()).isEqualTo(CompletenessItem.SKILLS);
		assertThat(rule.evaluate(SKILLS).nextStep()).isEqualTo(CompletenessItem.PROJECTS);
		// Con habilidades y proyectos: ACADEMIC y AVAILABILITY empatan en 15; gana ACADEMIC.
		ProfileFacts skillsAndProjects = new ProfileFacts(false, false, false, 3, false, 0, 1, false, false);
		assertThat(rule.evaluate(skillsAndProjects).nextStep()).isEqualTo(CompletenessItem.ACADEMIC);
		ProfileFacts withAcademic = new ProfileFacts(true, true, true, 3, false, 0, 1, false, false);
		assertThat(rule.evaluate(withAcademic).nextStep()).isEqualTo(CompletenessItem.AVAILABILITY);
		// LOCATION y ABOUT empatan en 10; gana LOCATION, y ABOUT queda para el final.
		ProfileFacts locationAndAbout = new ProfileFacts(true, true, true, 3, true, 1, 1, false, false);
		assertThat(rule.evaluate(locationAndAbout).nextStep()).isEqualTo(CompletenessItem.LOCATION);
		ProfileFacts onlyAboutMissing = new ProfileFacts(true, true, true, 3, true, 1, 1, true, false);
		assertThat(rule.evaluate(onlyAboutMissing).nextStep()).isEqualTo(CompletenessItem.ABOUT);
	}

	@Test
	@DisplayName("006-10: con 100 % el siguiente paso es nulo")
	void t006_10_nextStepIsNullWhenComplete() {
		assertThat(rule.evaluate(FULL).nextStep()).isNull();
	}

	@Test
	@DisplayName("006-09: los pesos y mínimos salen de la configuración: otros valores cambian el resultado")
	void t006_09_weightsAndMinimumsComeFromConfiguration() {
		CompletenessRule custom = new CompletenessRule(10, 10, 10, 10, 50, 10, 5, 2);

		assertThat(custom.evaluate(LOCATION).percent()).isEqualTo(50);
		assertThat(custom.evaluate(SKILLS).percent()).isZero();
		assertThat(custom.evaluate(AVAILABILITY).percent()).as("pide 2 modalidades, hay 1").isZero();
		assertThat(custom.evaluate(ProfileFacts.empty()).nextStep()).isEqualTo(CompletenessItem.LOCATION);
	}

	@Test
	@DisplayName("006-24: pesos que no suman 100, negativos o mínimos menores que 1 impiden arrancar")
	void t006_24_invalidConfigurationFailsFast() {
		assertThatThrownBy(() -> new CompletenessRule(15, 25, 15, 25, 10, 11, 3, 1))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100");
		assertThatThrownBy(() -> new CompletenessRule(-10, 50, 20, 20, 10, 10, 3, 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CompletenessRule(15, 25, 15, 25, 10, 10, 0, 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CompletenessRule(15, 25, 15, 25, 10, 10, 3, 0))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
