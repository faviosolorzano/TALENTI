package com.talenti.practice.domain;

import java.util.List;

/**
 * Textos FIJOS de DORA (no generados), versionados en {@code classpath:practice/dora-texts-v1.json}: saludo con la
 * identificación como IA (D-14, 012-05), avisos de privacidad (práctica, RF-35) y de evaluación (entrevista), cierre y
 * frases puente de plantilla (012-09). El aviso legal completo de la entrevista vive aparte, con su versión.
 */
public record DoraTexts(String version, String greeting, String greetingWithName, String practiceNotice,
		String interviewNotice, String closingPractice, String closingInterview, List<String> templateBridges,
		String templateBridgeLast) {

	/** Lo que DORA siempre dice de sí misma (012-05). */
	public static final String IDENTIFICATION = "Soy DORA, una asistente de inteligencia artificial";

	public DoraTexts {
		templateBridges = List.copyOf(templateBridges);
		if (templateBridges.isEmpty() || !greeting.contains(IDENTIFICATION)
				|| !greetingWithName.contains(IDENTIFICATION) || !greetingWithName.contains("{name}")) {
			throw new IllegalArgumentException("Los textos fijos de DORA están incompletos");
		}
	}

	/** Saludo + aviso. Con el primer nombre si existe (012-24); sin él, saludo sin nombre. */
	public String opening(String firstName, SessionMode mode) {
		String hello = firstName == null || firstName.isBlank() ? greeting : greetingWithName.replace("{name}", firstName);
		return hello + " " + (mode == SessionMode.INTERVIEW ? interviewNotice : practiceNotice);
	}

	public String closing(SessionMode mode) {
		return mode == SessionMode.INTERVIEW ? closingInterview : closingPractice;
	}

	/** Frase puente de plantilla para la respuesta a la pregunta {@code position}. */
	public String templateBridge(int position, boolean last) {
		return last ? templateBridgeLast : templateBridges.get(Math.floorMod(position - 1, templateBridges.size()));
	}

	@Override
	public String toString() {
		return "DoraTexts[version=" + version + "]";
	}

}
