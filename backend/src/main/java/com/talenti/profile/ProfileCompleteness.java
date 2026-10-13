package com.talenti.profile;

import java.util.List;

/**
 * Avance del perfil (RN-11), calculado por el código con la regla de 006-09: la suma de los pesos de los ítems
 * cumplidos, todo o nada por ítem. No lo calcula la IA ni el frontend.
 *
 * @param percent  de 0 a 100
 * @param items    los cinco ítems, en el orden de {@link CompletenessItem}
 * @param nextStep el pendiente de mayor peso (a igualdad, el primero en el orden del enum); nulo con 100 %
 */
public record ProfileCompleteness(int percent, List<Item> items, CompletenessItem nextStep) {

	public ProfileCompleteness {
		items = List.copyOf(items);
	}

	/** Un ítem del avance: su {@code key}, lo que vale y si está cumplido. */
	public record Item(CompletenessItem key, int weight, boolean done) {
	}

}
