package com.talenti.github.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Un lenguaje de un repositorio con sus bytes y su porcentaje (013-08). El porcentaje lo calcula el código: bytes del
 * lenguaje sobre el total, redondeado a 1 decimal, de mayor a menor.
 */
public record LanguageShare(String name, long bytes, BigDecimal percent) {

	/** Nombre de lenguaje más largo que se acepta de GitHub. */
	public static final int NAME_MAX = 100;

	/**
	 * Porcentajes por bytes. Ignora nombres vacíos o demasiado largos y cantidades negativas. Sin bytes en total, la
	 * lista queda vacía (un repositorio sin código se muestra sin lenguajes).
	 */
	public static List<LanguageShare> of(Map<String, Long> bytesByLanguage) {
		long total = 0;
		List<Map.Entry<String, Long>> valid = new ArrayList<>();
		for (Map.Entry<String, Long> entry : bytesByLanguage.entrySet()) {
			String name = entry.getKey() == null ? "" : entry.getKey().strip();
			Long bytes = entry.getValue();
			if (name.isEmpty() || name.length() > NAME_MAX || bytes == null || bytes <= 0) {
				continue;
			}
			valid.add(Map.entry(name, bytes));
			total += bytes;
		}
		if (total == 0) {
			return List.of();
		}
		BigDecimal sum = BigDecimal.valueOf(total);
		List<LanguageShare> result = new ArrayList<>();
		for (Map.Entry<String, Long> entry : valid) {
			BigDecimal percent = BigDecimal.valueOf(entry.getValue()).multiply(BigDecimal.valueOf(100))
					.divide(sum, 1, RoundingMode.HALF_UP);
			result.add(new LanguageShare(entry.getKey(), entry.getValue(), percent));
		}
		result.sort(Comparator.comparingLong(LanguageShare::bytes).reversed().thenComparing(LanguageShare::name));
		return List.copyOf(result);
	}

}
