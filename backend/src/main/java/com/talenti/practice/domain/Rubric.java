package com.talenti.practice.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rúbrica FIJA de la retroalimentación (RN-10, 012-18), versionada en {@code classpath:practice/rubric-v1.json}: cuatro
 * criterios (precisión técnica, claridad, estructura, ejemplos) con nivel 1 a 4. Los pesos del total son configuración
 * ({@code talenti.dora.rubric-weights}) y deben sumar 100. Una respuesta de la persona nunca cambia la rúbrica (012-20).
 */
public final class Rubric {

	public static final int MIN_LEVEL = 1;

	public static final int MAX_LEVEL = 4;

	/** Un criterio con su descripción y lo que significa cada nivel. */
	public record Criterion(String code, String name, String description) {
	}

	private final String version;

	private final List<Criterion> criteria;

	private final Map<String, Integer> weights;

	public Rubric(String version, List<Criterion> criteria, Map<String, Integer> weights) {
		this.version = version;
		this.criteria = List.copyOf(criteria);
		Map<String, Integer> byCode = new LinkedHashMap<>();
		int sum = 0;
		for (Criterion criterion : this.criteria) {
			Integer weight = weights.get(criterion.code());
			if (weight == null || weight < 0) {
				throw new IllegalArgumentException("Falta el peso del criterio " + criterion.code());
			}
			byCode.put(criterion.code(), weight);
			sum += weight;
		}
		if (this.criteria.isEmpty() || sum != 100) {
			throw new IllegalArgumentException("Los pesos de la rúbrica deben sumar 100");
		}
		this.weights = Map.copyOf(byCode);
	}

	public String version() {
		return version;
	}

	public List<Criterion> criteria() {
		return criteria;
	}

	public int weight(String code) {
		return weights.get(code);
	}

	public boolean has(String code) {
		return weights.containsKey(code);
	}

}
