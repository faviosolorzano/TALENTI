package com.talenti.exam.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import com.talenti.exam.domain.ExamQuestion.*;

/** Plano puro: no recibe perfiles ni decide con IA el reparto o los pesos. */
public record Blueprint(List<Slot> slots) {
	public record Technology(String slug, String name, boolean required, int minLevel) { }
	public record Slot(int position, Type type, String skillSlug, String skillName, int difficulty,
			BigDecimal points, Language language, CodeKind kind) { }
	public Blueprint { slots = List.copyOf(slots); }

	public static Blueprint create(List<Technology> technologies, Map<String, Language> languageMap,
			BigDecimal choiceWeight, BigDecimal openWeight, BigDecimal codeWeight) {
		var ordered = technologies.stream().sorted(Comparator.comparing(Technology::required).reversed()).toList();
		var supported = ordered.stream().filter(t -> languageMap.containsKey(t.slug())).toList();
		if (supported.isEmpty()) return new Blueprint(List.of());
		List<Slot> slots = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			Type type = i < 4 ? Type.CHOICE : i < 7 ? Type.OPEN : Type.CODE;
			var basis = type == Type.CODE ? supported : ordered;
			Technology technology = basis.get((type == Type.CODE ? i - 7 : i) % basis.size());
			slots.add(new Slot(i + 1, type, technology.slug(), technology.name(), technology.minLevel(),
					type == Type.CHOICE ? choiceWeight : type == Type.OPEN ? openWeight : codeWeight,
					type == Type.CODE ? languageMap.get(technology.slug()) : null,
					type == Type.CODE ? CodeKind.values()[i - 7] : null));
		}
		return new Blueprint(slots);
	}
}
