package com.talenti.matching.adapter;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import com.talenti.matching.domain.CompatibilityResult.Outcome;
import com.talenti.matching.domain.ExplanationDraft;
import com.talenti.matching.domain.ExplanationRequest;
import com.talenti.matching.port.CompatibilityExplainerPort;
import com.talenti.matching.port.ExplainerException;

/**
 * Adaptador SIMULADO de la explicación (015-16, principio 8): determinista y sin red. Guarda en memoria lo recibido (el
 * JSON exacto que iría a la IA) para que las pruebas comprueben qué viajó (015-04), y permite programar respuestas,
 * demoras y fallas. Esa memoria nunca se escribe en registros.
 *
 * <p>Sin programar, responde una explicación válida armada con lo recibido.
 */
public class SimulatedCompatibilityExplainer implements CompatibilityExplainerPort {

	public static final String ADAPTER = "simulated";

	public static final String MODEL = "modelo-simulado";

	public static final String PROMPT_VERSION = "explica-simulado-1";

	/** Cómo se comporta la próxima llamada. */
	public enum Mode {

		/** Responde bien (lo programado o la respuesta por defecto). */
		NORMAL,
		/** Falla enseguida (como un error HTTP). */
		FAIL,
		/** Responde algo que no es JSON del esquema. */
		MALFORMED,
		/** Se cuelga hasta que lo interrumpen. */
		HANG

	}

	private static final int JOURNAL_SIZE = 200;

	private final ConcurrentLinkedDeque<String> journal = new ConcurrentLinkedDeque<>();

	private final AtomicInteger calls = new AtomicInteger();

	private volatile Mode mode = Mode.NORMAL;

	private volatile Duration delay = Duration.ZERO;

	private volatile Function<ExplanationRequest, ExplanationDraft> responder = SimulatedCompatibilityExplainer::defaultDraft;

	@Override
	public String adapter() {
		return ADAPTER;
	}

	@Override
	public String model() {
		return MODEL;
	}

	@Override
	public String promptVersion() {
		return PROMPT_VERSION;
	}

	@Override
	public int instructionChars() {
		return 1200;
	}

	@Override
	public Answer explain(ExplanationRequest request) {
		String json = request.json();
		journal.addLast(json);
		while (journal.size() > JOURNAL_SIZE) {
			journal.pollFirst();
		}
		calls.incrementAndGet();
		sleep(delay);
		switch (mode) {
			case FAIL -> throw new ExplainerException(ExplainerException.Kind.CALL, "Falla simulada", 0);
			case MALFORMED -> throw new ExplainerException(ExplainerException.Kind.MALFORMED, "Respuesta simulada rota",
					17);
			case HANG -> sleep(Duration.ofMinutes(10));
			case NORMAL -> {
			}
		}
		ExplanationDraft draft = responder.apply(request);
		int chars = length(draft.fit()) + length(draft.gaps()) + length(draft.unknown());
		if (draft.practice() != null) {
			for (ExplanationDraft.Practice item : draft.practice()) {
				if (item.actions() != null) {
					for (String action : item.actions()) {
						chars += length(action);
					}
				}
			}
		}
		return new Answer(draft, chars);
	}

	// ------------------------------------------------------------------------------------------ para las pruebas

	public void mode(Mode mode) {
		this.mode = mode;
	}

	public void delay(Duration delay) {
		this.delay = delay == null ? Duration.ZERO : delay;
	}

	/** Programa lo que responde (hasta {@link #reset()}). */
	public void respond(Function<ExplanationRequest, ExplanationDraft> responder) {
		this.responder = responder;
	}

	/** Lo recibido, del más antiguo al más nuevo (el JSON exacto). */
	public List<String> journal() {
		return new ArrayList<>(journal);
	}

	public int calls() {
		return calls.get();
	}

	public void reset() {
		mode = Mode.NORMAL;
		delay = Duration.ZERO;
		responder = SimulatedCompatibilityExplainer::defaultDraft;
		journal.clear();
		calls.set(0);
	}

	/** Una explicación válida con lo recibido: coincidencias en {@code fit}, brechas en {@code gaps}. */
	public static ExplanationDraft defaultDraft(ExplanationRequest request) {
		List<String> matched = new ArrayList<>();
		List<String> missing = new ArrayList<>();
		for (ExplanationRequest.Requirement requirement : request.requirements()) {
			(requirement.outcome() == Outcome.MATCH ? matched : missing).add(requirement.skill());
		}
		String fit = matched.isEmpty() ? "Todavía no coincides con las habilidades de esta vacante, y eso se puede trabajar."
				: "Encajas porque ya manejas " + String.join(", ", matched) + " como pide la vacante.";
		String gaps = missing.isEmpty() ? "" : "Te conviene reforzar " + String.join(", ", missing) + ".";
		String unknown = request.missingInfo().isEmpty() ? ""
				: "Faltan algunos datos de tu perfil; completarlos ayuda a calcular mejor.";
		List<ExplanationDraft.Practice> practice = new ArrayList<>();
		for (ExplanationRequest.PriorityGap gap : request.priorityGaps()) {
			practice.add(new ExplanationDraft.Practice(gap.slug(), List.of("Practica " + gap.skill()
					+ " con un ejercicio corto.", "Explica en voz alta cómo usarías " + gap.skill() + ".")));
		}
		return new ExplanationDraft(fit, gaps, unknown, practice);
	}

	private static int length(String text) {
		return text == null ? 0 : text.length();
	}

	private static void sleep(Duration duration) {
		if (duration == null || duration.isZero() || duration.isNegative()) {
			return;
		}
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ExplainerException(ExplainerException.Kind.CALL, "Llamada simulada interrumpida", 0);
		}
	}

	@Override
	public String toString() {
		return "SimulatedCompatibilityExplainer[mode=" + mode + "]";
	}

}
