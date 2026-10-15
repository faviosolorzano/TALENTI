package com.talenti.cv.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 009-09 (endurecimiento): el reductor termina en milisegundos aunque el texto sea hostil (líneas larguísimas). */
class PersonalDataReducerPerformanceTests {

	private final PersonalDataReducer reducer = new PersonalDataReducer();

	private void assertFast(String text) {
		long start = System.nanoTime();
		String reduced = reducer.reduce(text);
		Duration took = Duration.ofNanos(System.nanoTime() - start);
		assertThat(took).as("tiempo para %d caracteres", text.length()).isLessThan(Duration.ofSeconds(5));
		assertThat(reduced).isNotNull();
	}

	@Test
	@DisplayName("009-09: 100 000 caracteres sin espacios (candidatos a correo) se reducen en menos de 5 s (antes tardaba de 14 a 100 s)")
	void t009_09_longEmailLikeLine() {
		assertFast("a".repeat(100_000));
		assertFast("a.".repeat(50_000));
		assertFast("a@".repeat(50_000));
	}

	@Test
	@DisplayName("009-09: 100 000 caracteres de fechas, números y direcciones incompletas se reducen en menos de 5 s (antes tardaba de 14 a 100 s)")
	void t009_09_longDateAndStreetLines() {
		assertFast("1/".repeat(50_000));
		assertFast("1 / 1 / ".repeat(12_000));
		assertFast("Av. a ".repeat(16_000));
		assertFast("1 ".repeat(50_000));
	}

	@Test
	@DisplayName("009-09: una línea larga se parte, pero un correo normal sigue quitándose")
	void t009_09_normalDataStillRemoved() {
		String text = "Programador con Java y SQL. " + "x".repeat(2_000) + "\nContacto ana.demo@ejemplo.test para detalles\n";

		String reduced = reducer.reduce(text);

		assertThat(reduced).doesNotContain("ana.demo@ejemplo.test").contains("Java");
	}

}
