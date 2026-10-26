package com.talenti.practice.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan de preguntas validado por el código (012-02 a 012-04). La IA propone; aquí se decide:
 * <ul>
 * <li>cada pregunta debe citar un {@code basisRef} de la lista enviada (si no, se reemplaza);</li>
 * <li>tiene que tener entre 10 y 500 caracteres y no tocar temas prohibidos ({@link ForbiddenTopics}, RN-09);</li>
 * <li>no se repiten preguntas;</li>
 * <li>la que no cumple, o la que falta, sale del banco base ({@code source = BANK}). Sin respuesta de la IA, todas
 * salen del banco y el plan queda degradado ({@code QUESTION_BANK}).</li>
 * </ul>
 * Código puro.
 */
public final class QuestionPlanner {

	public static final int MIN_CHARS = 10;

	public static final int MAX_CHARS = 500;

	/** Referencia de una pregunta general del banco (no cita ninguna habilidad, proyecto ni requisito). */
	public static final String GENERAL_REF = "general";

	/** Un elemento citable: referencia estable y nombre para mostrar. */
	public record BasisRef(String ref, String label) {

		@Override
		public String toString() {
			return "BasisRef[ref=" + ref + "]";
		}

	}

	/** Lo que propuso la IA (sin validar). */
	public record Proposal(String text, String basisRef) {

		@Override
		public String toString() {
			return "Proposal[...]";
		}

	}

	public record PlannedQuestion(int position, String text, QuestionSource source, String basisRef) {

		@Override
		public String toString() {
			return "PlannedQuestion[position=" + position + ", source=" + source + "]";
		}

	}

	/** @param degraded verdadero si la IA no respondió y todo salió del banco */
	public record Plan(List<PlannedQuestion> questions, boolean degraded) {

		public Plan {
			questions = List.copyOf(questions);
		}

	}

	private final QuestionBank bank;

	public QuestionPlanner(QuestionBank bank) {
		this.bank = bank;
	}

	/**
	 * @param basis     lo que se envió a la IA (o se habría enviado); puede estar vacío (solo preguntas generales)
	 * @param count     cuántas preguntas (1 a 5)
	 * @param proposals lo que devolvió la IA; {@code null} si no respondió, falló o no se la llamó
	 */
	public Plan plan(List<BasisRef> basis, int count, List<Proposal> proposals) {
		Map<String, String> labels = new LinkedHashMap<>();
		for (BasisRef item : basis) {
			labels.put(item.ref(), item.label());
		}
		Set<String> usedTexts = new HashSet<>();
		Set<String> usedFolded = new HashSet<>();
		Set<String> usedRefs = new HashSet<>();
		List<PlannedQuestion> result = new ArrayList<>();
		if (proposals != null) {
			for (Proposal proposal : proposals) {
				if (result.size() == count) {
					break;
				}
				String text = proposal == null || proposal.text() == null ? "" : proposal.text().strip();
				String ref = proposal == null || proposal.basisRef() == null ? "" : proposal.basisRef().strip();
				if (accepts(text, ref, labels, usedFolded)) {
					result.add(new PlannedQuestion(result.size() + 1, text, QuestionSource.AI, ref));
					usedTexts.add(text);
					usedFolded.add(Folding.fold(text));
					usedRefs.add(ref);
				}
			}
		}
		// Huecos: del banco, prefiriendo referencias aún sin pregunta.
		List<String> refs = new ArrayList<>(labels.keySet());
		int cursor = 0;
		while (result.size() < count) {
			String ref = null;
			for (int i = 0; i < refs.size(); i++) {
				String candidate = refs.get((cursor + i) % refs.size());
				if (!usedRefs.contains(candidate)) {
					ref = candidate;
					break;
				}
			}
			if (ref == null && !refs.isEmpty()) {
				ref = refs.get(cursor % refs.size());
			}
			cursor++;
			QuestionBank.Pick pick = ref == null ? new QuestionBank.Pick(bank.generalQuestion(usedTexts), true)
					: bank.questionFor(ref, labels.get(ref), usedTexts);
			String text = pick.text();
			String basisRef = pick.general() ? GENERAL_REF : ref;
			result.add(new PlannedQuestion(result.size() + 1, text, QuestionSource.BANK, basisRef));
			usedTexts.add(text);
			usedFolded.add(Folding.fold(text));
			if (ref != null) {
				usedRefs.add(ref);
			}
		}
		return new Plan(result, proposals == null);
	}

	private static boolean accepts(String text, String ref, Map<String, String> labels, Set<String> usedFolded) {
		return text.length() >= MIN_CHARS && text.length() <= MAX_CHARS && labels.containsKey(ref)
				&& !ForbiddenTopics.isForbiddenQuestion(text) && !usedFolded.contains(Folding.fold(text));
	}

}
