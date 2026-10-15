package com.talenti.cv.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.talenti.cv.RawSuggestion;
import com.talenti.cv.SuggestionKind;
import com.talenti.vacancy.SkillInfo;

/**
 * Valida la salida de CUALQUIER adaptador contra el esquema fijo y las reglas de 006 (009-13). La salida de un
 * adaptador, sobre todo la de una IA, no es de fiar: lo que no cumple, trae campos de más, está repetido o es
 * desmedido se descarta (la sugerencia, no el análisis). Lo que sobrevive es lo único que llega a la persona.
 *
 * <p>Los topes de texto son los de 006 y los de las columnas de la base (V5): carrera e instituto 80, proyecto 80 y
 * 500, tecnología 30, hasta 8 tecnologías, ciclo de 1 a 10, nivel de 1 a 3. Una habilidad fuera del catálogo se
 * entrega con {@code slug} nulo ("sin asociar", RN-03).
 */
public final class CvOutputValidator {

	public static final int ACADEMIC_MAX = 80;

	public static final int SKILL_NAME_MAX = 60;

	public static final int PROJECT_NAME_MAX = 80;

	public static final int PROJECT_DESCRIPTION_MAX = 500;

	public static final int TECHNOLOGIES_MAX = 8;

	public static final int TECHNOLOGY_MAX = 30;

	public static final int EVIDENCE_MAX = 200;

	public static final int MAX_PROJECTS = 10;

	public static final int MAX_SKILLS = 60;

	public static final int MAX_SUGGESTIONS = 100;

	private static final Set<String> FIELDS = Set.of("CAREER", "INSTITUTE", "CYCLE");

	private final Function<String, Optional<SkillInfo>> skillBySlug;

	private final Function<String, Optional<SkillInfo>> skillByText;

	private final int maxCycle;

	/**
	 * @param skillBySlug busca una habilidad del catálogo por su {@code slug}
	 * @param skillByText busca una habilidad del catálogo por su nombre o sinónimo
	 * @param maxCycle    ciclo más alto (el mínimo es 1)
	 */
	public CvOutputValidator(Function<String, Optional<SkillInfo>> skillBySlug,
			Function<String, Optional<SkillInfo>> skillByText, int maxCycle) {
		this.skillBySlug = skillBySlug;
		this.skillByText = skillByText;
		this.maxCycle = maxCycle;
	}

	/**
	 * Como {@link #validate(List)}, y además descarta toda sugerencia cuya evidencia no aparezca realmente en el texto
	 * que se leyó (sin distinguir mayúsculas, tildes ni espacios): una "frase del CV" inventada no respalda nada (T3).
	 */
	public List<SuggestionDraft> validate(List<RawSuggestion> raw, String sourceText) {
		String haystack = Fold.of(sourceText);
		return validate(raw).stream().filter(draft -> {
			String needle = Fold.of(draft.evidence());
			return !needle.isEmpty() && haystack.contains(needle);
		}).toList();
	}

	public List<SuggestionDraft> validate(List<RawSuggestion> raw) {
		List<SuggestionDraft> result = new ArrayList<>();
		if (raw == null) {
			return result;
		}
		Set<String> seen = new HashSet<>();
		int skills = 0;
		int projects = 0;
		for (RawSuggestion item : raw) {
			if (item == null || result.size() >= MAX_SUGGESTIONS) {
				continue;
			}
			SuggestionDraft draft = switch (kindOf(item.kind())) {
				case SKILL -> skills < MAX_SKILLS ? skill(item) : null;
				case ACADEMIC -> academic(item);
				case PROJECT -> projects < MAX_PROJECTS ? project(item) : null;
				case null -> null;
			};
			if (draft == null || !seen.add(key(draft))) {
				continue;
			}
			switch (draft.kind()) {
				case SKILL -> skills++;
				case PROJECT -> projects++;
				default -> {
				}
			}
			result.add(draft);
		}
		return result;
	}

	// ------------------------------------------------------------------------------------------ por tipo

	private SuggestionDraft skill(RawSuggestion item) {
		if (item.field() != null || item.value() != null || item.description() != null || item.technologies() != null) {
			return null;
		}
		String evidence = evidence(item.evidence());
		String name = text(item.name(), SKILL_NAME_MAX);
		if (evidence == null || name == null) {
			return null;
		}
		if (item.level() != null && (item.level() < 1 || item.level() > 3)) {
			return null;
		}
		SkillInfo known = null;
		String slug = item.slug() == null ? null : item.slug().strip();
		if (slug != null && !slug.isEmpty()) {
			known = skillBySlug.apply(slug).orElse(null);
		}
		if (known == null) {
			known = skillByText.apply(name).orElse(null);
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("slug", known == null ? null : known.slug());
		payload.put("name", known == null ? name : known.name());
		payload.put("level", item.level());
		return new SuggestionDraft(SuggestionKind.SKILL, null, payload, evidence);
	}

	private SuggestionDraft academic(RawSuggestion item) {
		if (item.slug() != null || item.name() != null || item.level() != null || item.description() != null
				|| item.technologies() != null) {
			return null;
		}
		String field = item.field() == null ? "" : item.field().strip().toUpperCase(java.util.Locale.ROOT);
		String evidence = evidence(item.evidence());
		if (!FIELDS.contains(field) || evidence == null) {
			return null;
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		if ("CYCLE".equals(field)) {
			Integer cycle = parseInt(item.value());
			if (cycle == null || cycle < 1 || cycle > maxCycle) {
				return null;
			}
			payload.put("value", cycle);
		}
		else {
			String value = text(item.value(), ACADEMIC_MAX);
			if (value == null || value.length() < 2) {
				return null;
			}
			payload.put("value", value);
		}
		return new SuggestionDraft(SuggestionKind.ACADEMIC, field, payload, evidence);
	}

	private SuggestionDraft project(RawSuggestion item) {
		if (item.field() != null || item.slug() != null || item.value() != null || item.level() != null) {
			return null;
		}
		String evidence = evidence(item.evidence());
		String name = text(item.name(), PROJECT_NAME_MAX);
		if (evidence == null || name == null || name.length() < 2) {
			return null;
		}
		String description = item.description() == null || item.description().isBlank() ? ""
				: text(item.description(), PROJECT_DESCRIPTION_MAX);
		if (description == null) {
			return null;
		}
		List<String> technologies = new ArrayList<>();
		if (item.technologies() != null) {
			if (item.technologies().size() > TECHNOLOGIES_MAX) {
				return null;
			}
			Set<String> seen = new HashSet<>();
			for (String technology : item.technologies()) {
				String clean = text(technology, TECHNOLOGY_MAX);
				if (clean == null) {
					return null;
				}
				if (seen.add(Fold.of(clean))) {
					technologies.add(clean);
				}
			}
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("name", name);
		payload.put("description", description);
		payload.put("technologies", technologies);
		return new SuggestionDraft(SuggestionKind.PROJECT, null, payload, evidence);
	}

	// ------------------------------------------------------------------------------------------ piezas

	private static SuggestionKind kindOf(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			return SuggestionKind.valueOf(raw.strip().toUpperCase(java.util.Locale.ROOT));
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Clave para descartar repetidos: mismo tipo y mismo contenido (o la misma habilidad / el mismo campo). */
	private static String key(SuggestionDraft draft) {
		return switch (draft.kind()) {
			case SKILL -> "S:" + (draft.payload().get("slug") != null ? draft.payload().get("slug")
					: "n:" + Fold.of(String.valueOf(draft.payload().get("name"))));
			case ACADEMIC -> "A:" + draft.field();
			case PROJECT -> "P:" + Fold.of(String.valueOf(draft.payload().get("name")));
		};
	}

	/** Texto limpio y acotado, o nulo si está vacío, tiene caracteres de control, o se pasa del máximo. */
	private static String text(String raw, int max) {
		if (raw == null) {
			return null;
		}
		String clean = Normalizer.normalize(raw, Normalizer.Form.NFC).replaceAll("[\\r\\n\\t]+", " ").strip()
				.replaceAll(" {2,}", " ");
		if (clean.isEmpty() || clean.codePoints().anyMatch(Character::isISOControl)) {
			return null;
		}
		if (clean.codePointCount(0, clean.length()) > max) {
			return null;
		}
		return clean;
	}

	/** La frase de respaldo: obligatoria; si es larga se corta a 200 caracteres (no se descarta la sugerencia). */
	private static String evidence(String raw) {
		if (raw == null) {
			return null;
		}
		String clean = Normalizer.normalize(raw, Normalizer.Form.NFC).replaceAll("[\\p{Cntrl}]+", " ").strip()
				.replaceAll(" {2,}", " ");
		if (clean.isEmpty()) {
			return null;
		}
		if (clean.length() > 1000) {
			return null;
		}
		return clean.length() > EVIDENCE_MAX ? clean.substring(0, EVIDENCE_MAX).strip() : clean;
	}

	private static Integer parseInt(String raw) {
		if (raw == null) {
			return null;
		}
		try {
			return Integer.valueOf(raw.strip());
		}
		catch (NumberFormatException e) {
			return null;
		}
	}

}
