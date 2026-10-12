package com.talenti.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/** Reloj de pruebas que solo avanza cuando se le pide: simula bloqueos de 15 minutos sin esperar. */
public final class MutableClock extends Clock {

	private final AtomicReference<Instant> now;

	public MutableClock(Instant start) {
		this.now = new AtomicReference<>(start);
	}

	public void advance(Duration duration) {
		now.updateAndGet(current -> current.plus(duration));
	}

	public void set(Instant instant) {
		now.set(instant);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return now.get();
	}

}
