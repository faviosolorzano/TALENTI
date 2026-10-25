package com.talenti.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.support.MutableClock;

/** 013-18 y 013-19: el presupuesto de llamadas de la API pública (código puro, con un reloj de pruebas). */
class RateBudgetTests {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-25T20:00:00Z"));

	private final RateBudget budget = new RateBudget(5, clock);

	private String in(Duration duration) {
		return Long.toString(clock.instant().plus(duration).getEpochSecond());
	}

	@Test
	@DisplayName("013-19: con más de 5 llamadas restantes se puede llamar; con 5 o menos, no hasta el reset")
	void t013_19_reserveBlocksUntilReset() {
		budget.record("6", in(Duration.ofMinutes(30)));
		assertThatCode(budget::check).doesNotThrowAnyException();

		budget.record("5", in(Duration.ofMinutes(30)));
		assertThatThrownBy(budget::check).isInstanceOf(GithubRateLimitedException.class)
				.satisfies(e -> assertThat(((GithubRateLimitedException) e).retryAt())
						.isEqualTo(Instant.parse("2026-10-25T20:30:00Z")));
		assertThat(budget.remaining()).hasValue(5);

		clock.advance(Duration.ofMinutes(30));
		assertThatCode(budget::check).doesNotThrowAnyException();
		assertThat(budget.remaining()).isEmpty();
	}

	@Test
	@DisplayName("013-19: un bloqueo por respuesta de límite dura hasta su hora; cabeceras ausentes o mal formadas se ignoran")
	void t013_19_blockAndBadHeaders() {
		budget.record(null, "no-es-numero");
		assertThatCode(budget::check).doesNotThrowAnyException();

		budget.block(clock.instant().plusSeconds(120));
		assertThatThrownBy(budget::check).isInstanceOf(GithubRateLimitedException.class);
		clock.advance(Duration.ofSeconds(121));
		assertThatCode(budget::check).doesNotThrowAnyException();
	}

}
