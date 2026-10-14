package com.talenti.cv.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 009-30: el texto que sale hacia la IA se limita cortando por líneas completas. */
class TextLimiterTests {

	@Test
	@DisplayName("009-30: un texto dentro del límite no se toca")
	void t009_30_withinTheLimit() {
		TextLimiter.Limited limited = TextLimiter.limit("Java\nSQL", 100);

		assertThat(limited.text()).isEqualTo("Java\nSQL");
		assertThat(limited.truncated()).isFalse();
	}

	@Test
	@DisplayName("009-30: un texto largo se corta por líneas completas, nunca pasa del máximo y avisa")
	void t009_30_cutsAtWholeLines() {
		String text = String.join("\n", "linea uno", "linea dos", "linea tres", "linea cuatro");

		TextLimiter.Limited limited = TextLimiter.limit(text, 25);

		assertThat(limited.text()).isEqualTo("linea uno\nlinea dos");
		assertThat(limited.text().length()).isLessThanOrEqualTo(25);
		assertThat(limited.truncated()).isTrue();
		assertThat(limited.toString()).doesNotContain("linea");
	}

	@Test
	@DisplayName("009-30: una sola línea más larga que el máximo se corta sin más")
	void t009_30_singleLongLine() {
		TextLimiter.Limited limited = TextLimiter.limit("x".repeat(500), 100);

		assertThat(limited.text()).hasSize(100);
		assertThat(limited.truncated()).isTrue();
	}

}
