package com.talenti.matching.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros de la fórmula de compatibilidad ({@code talenti.matching.*}), RN-14: viven en configuración, no en el
 * código. Los valores por defecto son los de RN-01 y los de la spec 007 (secciones 3 y 5). Si los cuatro pesos no
 * suman 100 el backend no arranca (007-09).
 */
@ConfigurationProperties(prefix = "talenti.matching")
public record MatchingProperties(
		@DefaultValue Weights weights,
		@DefaultValue("69") int cap,
		@DefaultValue("0.5") BigDecimal partialCredit,
		@DefaultValue Cosine cosine,
		@DefaultValue("50") int minEvaluableWeight,
		@DefaultValue Verdict verdict,
		@DefaultValue Similarity similarity) {

	public record Weights(
			@DefaultValue("50") int required,
			@DefaultValue("15") int desirable,
			@DefaultValue("20") int conditions,
			@DefaultValue("15") int similarity) {
	}

	public record Cosine(
			@DefaultValue("0.20") BigDecimal low,
			@DefaultValue("0.60") BigDecimal high) {
	}

	public record Verdict(
			@DefaultValue("80") int high,
			@DefaultValue("60") int medium) {
	}

	/** La similitud de proyectos con vectores sigue apagada hasta S12. */
	public record Similarity(@DefaultValue("false") boolean enabled) {
	}

}
