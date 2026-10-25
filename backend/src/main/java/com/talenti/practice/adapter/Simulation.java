package com.talenti.practice.adapter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

import com.talenti.practice.port.AiCallException;

/**
 * Piezas comunes de los adaptadores SIMULADOS (principio 8): deterministas, sin red, con demoras y fallas
 * programables. Guardan en memoria las últimas peticiones para que las pruebas comprueben qué viajó (por ejemplo, que
 * la evaluación nunca recibe el nombre, 012-24). Esa memoria nunca se escribe en registros.
 */
public final class Simulation {

	/** Cómo se comporta un adaptador simulado. */
	public enum Mode {

		/** Responde bien. */
		NORMAL,
		/** Falla enseguida. */
		FAIL,
		/** Se cuelga hasta que lo interrumpen. */
		HANG,
		/** Responde con una salida que intenta romper las reglas (012-20). */
		HOSTILE

	}

	private static final int JOURNAL_SIZE = 500;

	private final ConcurrentLinkedDeque<String> journal = new ConcurrentLinkedDeque<>();

	Simulation() {
	}

	void remember(String request) {
		journal.addLast(request);
		while (journal.size() > JOURNAL_SIZE) {
			journal.pollFirst();
		}
	}

	/** Las últimas peticiones recibidas, de la más antigua a la más nueva. */
	public List<String> journal() {
		return new ArrayList<>(journal);
	}

	public void clearJournal() {
		journal.clear();
	}

	/** Espera la demora; si la interrumpen, falla como una llamada cortada. */
	static void delay(Duration duration) {
		if (duration == null || duration.isZero() || duration.isNegative()) {
			return;
		}
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AiCallException("Llamada simulada interrumpida");
		}
	}

	/** Se cuelga hasta que lo interrumpen (como un servicio que no responde). */
	static void hang() {
		delay(Duration.ofMinutes(10));
	}

}
