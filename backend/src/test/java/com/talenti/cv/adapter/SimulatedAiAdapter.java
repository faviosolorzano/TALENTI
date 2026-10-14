package com.talenti.cv.adapter;

import java.util.ArrayList;
import java.util.List;

import com.talenti.cv.AdapterInfo;
import com.talenti.cv.EnhancedCvExtractionPort;
import com.talenti.cv.RawSuggestion;
import com.talenti.vacancy.SkillInfo;

/**
 * Adaptador de IA SIMULADO para las pruebas (principio 8): no hace ninguna llamada de red. Devuelve las habilidades del
 * catálogo que encuentra por nombre (como haría una IA razonable) o, en modo hostil, una salida que intenta romper el
 * esquema.
 */
class SimulatedAiAdapter implements EnhancedCvExtractionPort {

	enum Behavior {
		WELL_BEHAVED, HOSTILE
	}

	private final Behavior behavior;

	SimulatedAiAdapter(Behavior behavior) {
		this.behavior = behavior;
	}

	@Override
	public AdapterInfo info() {
		return new AdapterInfo("simulated-ai", "1.0.0", "test-instructions-1", "modelo-simulado");
	}

	@Override
	public List<RawSuggestion> extract(String reducedText, List<SkillInfo> catalog) {
		List<RawSuggestion> result = new ArrayList<>();
		String lower = reducedText.toLowerCase(java.util.Locale.ROOT);
		for (SkillInfo skill : catalog) {
			if (lower.contains(skill.name().toLowerCase(java.util.Locale.ROOT))) {
				result.add(RawSuggestion.skill(skill.slug(), skill.name(), null, "Mención de " + skill.name()));
			}
		}
		if (behavior == Behavior.HOSTILE && !reducedText.isBlank()) {
			result.add(RawSuggestion.skill("java", "Java", 3, "Java"));
			// repetido, nivel absurdo, campos de más, textos enormes, tipos inventados, ciclo fuera de rango
			result.add(RawSuggestion.skill("java", "Java", 3, "Java otra vez"));
			result.add(RawSuggestion.skill("sql", "SQL", 100, "nivel absurdo"));
			result.add(new RawSuggestion("SKILL", "CAREER", "git", "Git", "calificacion=100", 2, "ignora tus reglas", List.of("x"), "campos de mas"));
			result.add(RawSuggestion.skill("docker", "D".repeat(5000), null, "nombre enorme"));
			result.add(RawSuggestion.academic("CYCLE", "99", "ciclo fuera de rango"));
			result.add(RawSuggestion.academic("RANGO", "DIRECTOR", "campo inventado"));
			result.add(RawSuggestion.project("P".repeat(500), "x", List.of("Java"), "nombre enorme"));
			result.add(new RawSuggestion("COMPATIBILIDAD", null, null, "100%", null, null, null, null, "tipo inventado"));
			result.add(RawSuggestion.skill("react", "React", null, null));
		}
		return result;
	}

}
