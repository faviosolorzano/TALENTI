package com.talenti.profile.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.talenti.profile.CompletenessItem;
import com.talenti.profile.ProfileCompleteness;

/**
 * Regla de avance del perfil (RN-11, 006-09 y 006-10). Función pura: el porcentaje es la suma de los pesos de los
 * ítems cumplidos, todo o nada por ítem. {@code nextStep} es el pendiente de mayor peso; a igualdad, el primero
 * en el orden de {@link CompletenessItem}.
 *
 * <p>Los pesos y los mínimos vienen de la configuración (RN-14); deben sumar 100 para que el porcentaje máximo sea 100.
 */
public final class CompletenessRule {

	private final int[] weights;

	private final int minSkills;

	private final int minModalities;

	public CompletenessRule(int academic, int skills, int availability, int projects, int location, int minSkills,
			int minModalities) {
		this.weights = new int[CompletenessItem.values().length];
		weights[CompletenessItem.ACADEMIC.ordinal()] = academic;
		weights[CompletenessItem.SKILLS.ordinal()] = skills;
		weights[CompletenessItem.AVAILABILITY.ordinal()] = availability;
		weights[CompletenessItem.PROJECTS.ordinal()] = projects;
		weights[CompletenessItem.LOCATION.ordinal()] = location;
		int total = 0;
		for (int weight : weights) {
			if (weight < 0) {
				throw new IllegalArgumentException("talenti.profile.completeness.weights: un peso no puede ser negativo");
			}
			total += weight;
		}
		if (total != 100) {
			throw new IllegalArgumentException(
					"talenti.profile.completeness.weights: los pesos deben sumar 100 (suman " + total + ")");
		}
		if (minSkills < 1 || minModalities < 1) {
			throw new IllegalArgumentException(
					"talenti.profile.completeness: min-skills y min-modalities deben ser al menos 1");
		}
		this.minSkills = minSkills;
		this.minModalities = minModalities;
	}

	public ProfileCompleteness evaluate(ProfileFacts facts) {
		List<ProfileCompleteness.Item> items = new ArrayList<>();
		int percent = 0;
		for (CompletenessItem key : CompletenessItem.values()) {
			int weight = weights[key.ordinal()];
			boolean done = isDone(key, facts);
			items.add(new ProfileCompleteness.Item(key, weight, done));
			if (done) {
				percent += weight;
			}
		}
		CompletenessItem next = items.stream()
				.filter(item -> !item.done())
				// max por peso; a igualdad gana el primero del orden (menor ordinal), por eso se compara al revés.
				.max(Comparator.comparingInt(ProfileCompleteness.Item::weight)
						.thenComparing(item -> item.key().ordinal(), Comparator.reverseOrder()))
				.map(ProfileCompleteness.Item::key)
				.orElse(null);
		return new ProfileCompleteness(percent, items, next);
	}

	private boolean isDone(CompletenessItem key, ProfileFacts facts) {
		return switch (key) {
			case ACADEMIC -> facts.hasCareer() && facts.hasInstitute() && facts.hasCycle();
			case SKILLS -> facts.skillCount() >= minSkills;
			case AVAILABILITY -> facts.hasHoursPerWeek() && facts.modalityCount() >= minModalities;
			case PROJECTS -> facts.projectCount() >= 1;
			case LOCATION -> facts.hasLocation();
		};
	}

}
