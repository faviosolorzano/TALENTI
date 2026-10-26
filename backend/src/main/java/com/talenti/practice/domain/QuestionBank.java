package com.talenti.practice.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Banco base de preguntas (012-04), versionado en {@code classpath:practice/question-bank-v1.json}: preguntas por
 * {@code slug} de habilidad, plantillas para cualquier habilidad o proyecto ({@code {name}}) y preguntas generales. Es
 * el respaldo cuando la IA no responde, falla, se agota el gasto o propone una pregunta que no pasa los filtros. Sus
 * textos ya pasan {@link ForbiddenTopics} (lo comprueba una prueba).
 */
public final class QuestionBank {

	private final String version;

	private final Map<String, List<String>> bySkill;

	private final List<String> anySkill;

	private final List<String> anyProject;

	private final List<String> general;

	public QuestionBank(String version, Map<String, List<String>> bySkill, List<String> anySkill,
			List<String> anyProject, List<String> general) {
		if (version == null || version.isBlank() || anySkill.isEmpty() || anyProject.isEmpty() || general.size() < 5) {
			throw new IllegalArgumentException("El banco base de preguntas está incompleto");
		}
		this.version = version;
		this.bySkill = Map.copyOf(bySkill);
		this.anySkill = List.copyOf(anySkill);
		this.anyProject = List.copyOf(anyProject);
		this.general = List.copyOf(general);
	}

	public String version() {
		return version;
	}

	/** Todos los textos (para comprobar el banco en las pruebas). */
	public List<String> allTexts() {
		java.util.ArrayList<String> all = new java.util.ArrayList<>();
		bySkill.values().forEach(all::addAll);
		all.addAll(anySkill);
		all.addAll(anyProject);
		all.addAll(general);
		return all;
	}

	/** Una pregunta del banco; {@code general} si no es de la referencia pedida. */
	public record Pick(String text, boolean general) {
	}

	/**
	 * Una pregunta para la referencia ({@code skill:<slug>}, {@code requirement:<slug>} o {@code project:<n>}) que no
	 * esté en {@code used}; si no queda ninguna, una general.
	 */
	public Pick questionFor(String basisRef, String label, Set<String> used) {
		if (basisRef != null && label != null) {
			int colon = basisRef.indexOf(':');
			String kind = colon < 0 ? "" : basisRef.substring(0, colon);
			String key = colon < 0 ? "" : basisRef.substring(colon + 1);
			List<String> candidates = new java.util.ArrayList<>();
			if (kind.equals("skill") || kind.equals("requirement")) {
				candidates.addAll(bySkill.getOrDefault(key, List.of()));
				anySkill.forEach(template -> candidates.add(template.replace("{name}", label)));
			}
			else if (kind.equals("project")) {
				anyProject.forEach(template -> candidates.add(template.replace("{name}", label)));
			}
			for (String candidate : candidates) {
				if (!used.contains(candidate)) {
					return new Pick(candidate, false);
				}
			}
		}
		return new Pick(generalQuestion(used), true);
	}

	/** Una pregunta general que no esté en {@code used}. */
	public String generalQuestion(Set<String> used) {
		for (String candidate : general) {
			if (!used.contains(candidate)) {
				return candidate;
			}
		}
		return general.get(used.size() % general.size());
	}

}
