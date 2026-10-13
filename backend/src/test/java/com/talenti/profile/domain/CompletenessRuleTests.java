package com.talenti.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.profile.CompletenessItem;
import com.talenti.profile.ProfileCompleteness;

/** Regla de avance del perfil (RN-11): 006-09 (porcentaje, todo o nada) y 006-10 (siguiente paso y desempates). */
class CompletenessRuleTests {

	/** Los valores por defecto de la spec: 20, 25, 20, 25 y 10; al menos 3 habilidades y 1 modalidad. */
	private final CompletenessRule rule = new CompletenessRule(20, 25, 20, 25, 10, 3, 1);

	private static final ProfileFacts ACADEMIC = new ProfileFacts(true, true, true, 0, false, 0, 0, false);

	private static final ProfileFacts SKILLS = new ProfileFacts(false, false, false, 3, false, 0, 0, false);

	private static final ProfileFacts AVAILABILITY = new ProfileFacts(false, false, false, 0, true, 1, 0, false);

	private static final ProfileFacts PROJECTS = new ProfileFacts(false, false, false, 0, false, 0, 1, false);

	private static final ProfileFacts LOCATION = new ProfileFacts(false, false, false, 0, false, 0, 0, true);

	private static final ProfileFacts FULL = new ProfileFacts(true, true, true, 3, true, 1, 1, true);

	@Test
	@DisplayName("006-09: un perfil vacío tiene 0 % y los cinco ítems pendientes, en el orden de la spec")
	void t006_09_emptyProfileIsZeroPercent() {
		ProfileCompleteness result = rule.evaluate(ProfileFacts.empty());

		assertThat(result.percent()).isZero();
		assertThat(result.items()).extracting(ProfileCompleteness.Item::key).containsExactly(CompletenessItem.ACADEMIC,
				CompletenessItem.SKILLS, CompletenessItem.AVAILABILITY, CompletenessItem.PROJECTS,
				CompletenessItem.LOCATION);
		assertThat(result.items()).extracting(ProfileCompleteness.Item::weight).containsExactly(20, 25, 20, 25, 10);
		assertThat(result.items()).allMatch(item -> !item.done());
	}

	@Test
	@DisplayName("006-09: cada ítem cumplido suma exactamente su peso (20, 25, 20, 25 y 10)")
	void t006_09_eachItemAddsItsWeight() {
		assertThat(rule.evaluate(ACADEMIC).percent()).isEqualTo(20);
		assertThat(rule.evaluate(SKILLS).percent()).isEqualTo(25);
		assertThat(rule.evaluate(AVAILABILITY).percent()).isEqualTo(20);
		assertThat(rule.evaluate(PROJECTS).percent()).isEqualTo(25);
		assertThat(rule.evaluate(LOCATION).percent()).isEqualTo(10);
	}

	@Test
	@DisplayName("006-09: con todo cumplido el avance es 100 % y no queda ningún pendiente")
	void t006_09_fullProfileIsOneHundredPercent() {
		ProfileCompleteness result = rule.evaluate(FULL);

		assertThat(result.percent()).isEqualTo(100);
		assertThat(result.items()).allMatch(ProfileCompleteness.Item::done);
	}

	@Test
	@DisplayName("006-09: los datos académicos son todo o nada: con 2 de 3 campos no suma")
	void t006_09_academicNeedsAllThreeFields() {
		assertThat(rule.evaluate(new ProfileFacts(true, true, false, 0, false, 0, 0, false)).percent()).isZero();
		assertThat(rule.evaluate(new ProfileFacts(true, false, true, 0, false, 0, 0, false)).percent()).isZero();
		assertThat(rule.evaluate(new ProfileFacts(false, true, true, 0, false, 0, 0, false)).percent()).isZero();
	}

	@Test
	@DisplayName("006-09: las habilidades son todo o nada: 2 no suman, 3 suman los 25 completos")
	void t006_09_skillsNeedThreeAndGiveNoPartialCredit() {
		assertThat(rule.evaluate(new ProfileFacts(false, false, false, 2, false, 0, 0, false)).percent()).isZero();
		assertThat(rule.evaluate(new ProfileFacts(false, false, false, 3, false, 0, 0, false)).percent()).isEqualTo(25);
		assertThat(rule.evaluate(new ProfileFacts(false, false, false, 9, false, 0, 0, false)).percent()).isEqualTo(25);
	}

	@Test
	@DisplayName("006-09: la disponibilidad pide horas Y al menos una modalidad; una sola cosa no suma")
	void t006_09_availabilityNeedsHoursAndModality() {
		assertThat(rule.evaluate(new ProfileFacts(false, false, false, 0, true, 0, 0, false)).percent()).isZero();
		assertThat(rule.evaluate(new ProfileFacts(false, false, false, 0, false, 2, 0, false)).percent()).isZero();
		assertThat(rule.evaluate(new ProfileFacts(false, false, false, 0, true, 3, 0, false)).percent()).isEqualTo(20);
	}

	@Test
	@DisplayName("006-09: la ubicación suma por separado de la disponibilidad")
	void t006_09_locationIsIndependentFromAvailability() {
		ProfileCompleteness result = rule.evaluate(new ProfileFacts(false, false, false, 0, true, 1, 0, true));

		assertThat(result.percent()).isEqualTo(30);
	}

	@Test
	@DisplayName("006-09: para las 32 combinaciones, el porcentaje es la suma de los pesos de lo cumplido")
	void t006_09_everyCombinationSumsItsWeights() {
		int[] weights = { 20, 25, 20, 25, 10 };
		for (int mask = 0; mask < 32; mask++) {
			boolean academic = (mask & 1) != 0;
			boolean skills = (mask & 2) != 0;
			boolean availability = (mask & 4) != 0;
			boolean projects = (mask & 8) != 0;
			boolean location = (mask & 16) != 0;
			ProfileFacts facts = new ProfileFacts(academic, academic, academic, skills ? 3 : 0, availability,
					availability ? 1 : 0, projects ? 1 : 0, location);
			int expected = 0;
			for (int i = 0; i < 5; i++) {
				if ((mask & (1 << i)) != 0) {
					expected += weights[i];
				}
			}

			assertThat(rule.evaluate(facts).percent()).as("combinación %d", mask).isEqualTo(expected);
		}
	}

	@Test
	@DisplayName("006-10: el siguiente paso es el pendiente de mayor peso; a igualdad, el primero del orden (habilidades antes que proyectos)")
	void t006_10_nextStepIsHeaviestPendingWithTieBreak() {
		// Vacío: SKILLS (25) y PROJECTS (25) empatan; gana SKILLS por orden.
		assertThat(rule.evaluate(ProfileFacts.empty()).nextStep()).isEqualTo(CompletenessItem.SKILLS);
		// Con habilidades: queda PROJECTS (25), el de mayor peso.
		assertThat(rule.evaluate(SKILLS).nextStep()).isEqualTo(CompletenessItem.PROJECTS);
		// Con habilidades y proyectos: ACADEMIC y AVAILABILITY empatan en 20; gana ACADEMIC.
		ProfileFacts skillsAndProjects = new ProfileFacts(false, false, false, 3, false, 0, 1, false);
		assertThat(rule.evaluate(skillsAndProjects).nextStep()).isEqualTo(CompletenessItem.ACADEMIC);
		// Con además lo académico: AVAILABILITY (20) antes que LOCATION (10).
		ProfileFacts withAcademic = new ProfileFacts(true, true, true, 3, false, 0, 1, false);
		assertThat(rule.evaluate(withAcademic).nextStep()).isEqualTo(CompletenessItem.AVAILABILITY);
		// Solo falta la ubicación.
		ProfileFacts onlyLocationMissing = new ProfileFacts(true, true, true, 3, true, 1, 1, false);
		assertThat(rule.evaluate(onlyLocationMissing).nextStep()).isEqualTo(CompletenessItem.LOCATION);
	}

	@Test
	@DisplayName("006-10: con 100 % el siguiente paso es nulo")
	void t006_10_nextStepIsNullWhenComplete() {
		assertThat(rule.evaluate(FULL).nextStep()).isNull();
	}

	@Test
	@DisplayName("006-09: los pesos y mínimos salen de la configuración: otros valores cambian el resultado")
	void t006_09_weightsAndMinimumsComeFromConfiguration() {
		CompletenessRule custom = new CompletenessRule(10, 10, 10, 10, 60, 5, 2);

		assertThat(custom.evaluate(LOCATION).percent()).isEqualTo(60);
		assertThat(custom.evaluate(SKILLS).percent()).isZero();
		assertThat(custom.evaluate(new ProfileFacts(false, false, false, 5, false, 0, 0, false)).percent())
				.isEqualTo(10);
		assertThat(custom.evaluate(AVAILABILITY).percent()).as("pide 2 modalidades, hay 1").isZero();
		assertThat(custom.evaluate(ProfileFacts.empty()).nextStep()).isEqualTo(CompletenessItem.LOCATION);
	}

	@Test
	@DisplayName("006-09: pesos que no suman 100, negativos o mínimos menores que 1 impiden arrancar")
	void t006_09_invalidConfigurationFailsFast() {
		assertThatThrownBy(() -> new CompletenessRule(20, 25, 20, 25, 11, 3, 1))
				.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100");
		assertThatThrownBy(() -> new CompletenessRule(-10, 50, 20, 25, 15, 3, 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CompletenessRule(20, 25, 20, 25, 10, 0, 1))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CompletenessRule(20, 25, 20, 25, 10, 3, 0))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("006-09: el resultado es inmutable (la lista de ítems no se puede modificar)")
	void t006_09_resultIsImmutable() {
		ProfileCompleteness result = rule.evaluate(FULL);

		assertThatThrownBy(() -> result.items().add(new ProfileCompleteness.Item(CompletenessItem.ACADEMIC, 1, true)))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThat(List.copyOf(result.items())).hasSize(5);
	}

}
