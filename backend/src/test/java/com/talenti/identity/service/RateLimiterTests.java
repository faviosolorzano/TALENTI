package com.talenti.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.shared.web.TooManyRequestsException;
import com.talenti.support.MutableClock;

/** 001-11: límite de 10 solicitudes de ingreso por minuto y por origen, con reloj simulado. */
class RateLimiterTests {

	private final MutableClock clock = new MutableClock(Instant.parse("2026-10-11T12:00:00Z"));

	private final RateLimiter limiter = new RateLimiter(10, Duration.ofMinutes(1), clock);

	@Test
	@DisplayName("001-11: la solicitud 11 del mismo origen en un minuto da 429 con Retry-After")
	void t001_11_eleventhRequestIsRejected() {
		for (int i = 0; i < 10; i++) {
			limiter.check("203.0.113.7");
			clock.advance(Duration.ofSeconds(1));
		}

		assertThatThrownBy(() -> limiter.check("203.0.113.7")).isInstanceOfSatisfying(TooManyRequestsException.class,
				e -> {
					assertThat(e.getStatus().value()).isEqualTo(429);
					assertThat(Long.parseLong(e.getHeaders().getFirst("Retry-After"))).isBetween(1L, 60L);
				});
	}

	@Test
	@DisplayName("001-11: otro origen tiene su propio contador")
	void t001_11_originsAreIndependent() {
		for (int i = 0; i < 10; i++) {
			limiter.check("203.0.113.7");
		}
		assertThatCode(() -> limiter.check("203.0.113.8")).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("001-11: al terminar la ventana el origen vuelve a poder intentar")
	void t001_11_windowResets() {
		for (int i = 0; i < 11; i++) {
			try {
				limiter.check("203.0.113.7");
			}
			catch (TooManyRequestsException expected) {
				// la solicitud 11
			}
		}
		clock.advance(Duration.ofSeconds(59));
		assertThatThrownBy(() -> limiter.check("203.0.113.7")).isInstanceOf(TooManyRequestsException.class);

		clock.advance(Duration.ofSeconds(1));
		assertThatCode(() -> limiter.check("203.0.113.7")).doesNotThrowAnyException();
	}

}
