package com.talenti.practice.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Percentiles por el método del rango más cercano (012-15): p50 y p95 de los tiempos de los últimos turnos. */
public final class Percentiles {

	private Percentiles() {
	}

	/**
	 * @param values    muestras (no se modifica la lista)
	 * @param percentile de 0 a 100
	 * @return el percentil, o nulo si no hay muestras
	 */
	public static Long of(List<? extends Number> values, double percentile) {
		if (values.isEmpty()) {
			return null;
		}
		if (percentile < 0 || percentile > 100) {
			throw new IllegalArgumentException("percentil fuera de rango");
		}
		List<Long> sorted = new ArrayList<>();
		for (Number value : values) {
			sorted.add(value.longValue());
		}
		Collections.sort(sorted);
		int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
		return sorted.get(Math.max(0, rank - 1));
	}

}
