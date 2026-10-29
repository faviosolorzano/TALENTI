package com.talenti.practice.domain;

import java.util.List;

/**
 * Las instrucciones de la entrevistadora en tiempo real (012-41), armadas desde el recurso versionado
 * {@code realtime-interviewer-v1}. Llevan SOLO el primer nombre (D-42) y el plan de preguntas del servidor
 * ({@code practice_question}); nunca apellidos, correo, teléfono, DNI ni datos de otras personas. Código puro.
 */
public final class RealtimeInstructions {

	/** Cómo arranca la llamada: saludo (primera) o "retomemos" (reconexión, 012-52). */
	public enum Start {

		GREETING, RESUME

	}

	/** Las piezas del recurso versionado. */
	public record Template(String version, String base, String kindPractice, String kindInterview,
			String greetingWithName, String greetingWithoutName, String practiceNotice, String interviewNotice,
			String practiceComment, String interviewComment, String startGreeting, String startResume,
			String startResumeClosing) {

		@Override
		public String toString() {
			return "Template[version=" + version + "]";
		}

	}

	/** Una pregunta del plan, con su texto del servidor. */
	public record PlannedQuestion(int position, String text) {

		@Override
		public String toString() {
			return "PlannedQuestion[position=" + position + "]";
		}

	}

	/**
	 * @param firstName     primer nombre o nulo (DORA saluda sin nombre)
	 * @param startPosition primera pregunta sin respuesta al retomar; nula si ya se respondieron todas
	 */
	public record Request(SessionMode mode, String firstName, List<PlannedQuestion> questions, Start start,
			Integer startPosition, int followUpsPerQuestion) {

		@Override
		public String toString() {
			return "Request[mode=" + mode + ", start=" + start + "]";
		}

	}

	private static final int MAX_NAME = 40;

	private final Template template;

	public RealtimeInstructions(Template template) {
		this.template = template;
	}

	public String version() {
		return template.version();
	}

	public String build(Request request) {
		boolean interview = request.mode() == SessionMode.INTERVIEW;
		String name = safeName(request.firstName());
		String greeting = name == null ? template.greetingWithoutName()
				: template.greetingWithName().replace("{name}", name);
		StringBuilder plan = new StringBuilder();
		for (PlannedQuestion question : request.questions()) {
			plan.append("   ").append(question.position()).append(". ").append(oneLine(question.text())).append('\n');
		}
		String start = switch (request.start()) {
			case GREETING -> template.startGreeting();
			case RESUME -> request.startPosition() == null ? template.startResumeClosing()
					: template.startResume().replace("{position}", String.valueOf(request.startPosition()));
		};
		return template.base().replace("{kind}", interview ? template.kindInterview() : template.kindPractice())
				.replace("{greeting}", greeting)
				.replace("{notice}", interview ? template.interviewNotice() : template.practiceNotice())
				.replace("{plan}", plan.toString().stripTrailing())
				.replace("{followUps}", String.valueOf(request.followUpsPerQuestion()))
				.replace("{comment}", interview ? template.interviewComment() : template.practiceComment())
				.replace("{start}", start);
	}

	/** Solo letras, espacios, guion y apóstrofo; corto. Un nombre raro no puede colar instrucciones. */
	static String safeName(String firstName) {
		if (firstName == null) {
			return null;
		}
		StringBuilder safe = new StringBuilder();
		for (char c : firstName.strip().toCharArray()) {
			if (Character.isLetter(c) || c == ' ' || c == '-' || c == '\'') {
				safe.append(c);
			}
		}
		String value = safe.toString().strip();
		if (value.length() > MAX_NAME) {
			value = value.substring(0, MAX_NAME).strip();
		}
		return value.isEmpty() ? null : value;
	}

	private static String oneLine(String text) {
		return text == null ? "" : text.replaceAll("\\s+", " ").strip();
	}

}
