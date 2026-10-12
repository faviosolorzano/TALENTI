package com.talenti.support;

import java.time.Instant;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Sustituye el reloj de la aplicación por uno que las pruebas pueden adelantar. */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfiguration {

	@Bean
	@Primary
	MutableClock mutableClock() {
		return new MutableClock(Instant.now());
	}

}
