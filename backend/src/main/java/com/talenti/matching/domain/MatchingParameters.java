package com.talenti.matching.domain;

import java.math.BigDecimal;

/**
 * Parámetros de la fórmula (RN-01, RN-14): viven en configuración ({@code talenti.matching.*}), no en el código.
 * Si no son coherentes (por ejemplo, los cuatro pesos no suman 100) la aplicación no arranca.
 *
 * @param partialCredit   crédito de un requisito que el perfil tiene en un nivel menor al pedido
 * @param cap             máximo cuando falta un requisito obligatorio
 * @param minEvaluableWeight peso mínimo de componentes evaluables para mostrar un porcentaje (RN-02)
 * @param verdictHigh     desde este porcentaje el veredicto es {@code HIGH}
 * @param verdictMedium   desde este porcentaje (y por debajo de {@code verdictHigh}) es {@code MEDIUM}
 */
public record MatchingParameters(int weightRequired, int weightDesirable, int weightConditions, int weightSimilarity,
		int cap, BigDecimal partialCredit, BigDecimal cosineLow, BigDecimal cosineHigh, int minEvaluableWeight,
		int verdictHigh, int verdictMedium, boolean similarityEnabled) {

	public MatchingParameters {
		if (weightRequired < 0 || weightDesirable < 0 || weightConditions < 0 || weightSimilarity < 0) {
			throw new IllegalArgumentException("talenti.matching.weights: ningún peso puede ser negativo");
		}
		if (weightRequired + weightDesirable + weightConditions + weightSimilarity != 100) {
			throw new IllegalArgumentException("talenti.matching.weights: los cuatro pesos deben sumar 100");
		}
		if (cap < 0 || cap > 100) {
			throw new IllegalArgumentException("talenti.matching.cap: debe estar entre 0 y 100");
		}
		if (partialCredit.signum() < 0 || partialCredit.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException("talenti.matching.partial-credit: debe estar entre 0 y 1");
		}
		if (cosineLow.compareTo(cosineHigh) >= 0) {
			throw new IllegalArgumentException("talenti.matching.cosine: low debe ser menor que high");
		}
		if (minEvaluableWeight < 0 || minEvaluableWeight > 100) {
			throw new IllegalArgumentException("talenti.matching.min-evaluable-weight: debe estar entre 0 y 100");
		}
		if (verdictMedium < 0 || verdictHigh > 100 || verdictMedium > verdictHigh) {
			throw new IllegalArgumentException("talenti.matching.verdict: 0 <= medium <= high <= 100");
		}
	}

}
