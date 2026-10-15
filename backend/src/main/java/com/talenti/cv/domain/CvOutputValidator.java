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

	/** Motivos de descarte que se cuentan (solo conteos, nunca contenido). */
	public static final String R_SHAPE = "CAMPOS_QUE_NO_CORRESPONDEN";

	public static final String R_VALUE = "VALOR_NO_VALIDO";

	public static final String R_EVIDENCE = "EVIDENCIA_NO_ESTA_EN_EL_TEXTO";

	public static final String R_SUBJECT = "HABILIDAD_NO_ESTA_EN_EL_TEXTO";

	public static final String R_DUPLICATE = "REPETIDA";

	public static final String R_LIMIT = "PASA_EL_TOPE";

	private static final Set<String> STOP_WORDS = Set.of("de", "del", "la", "las", "el", "los", "un", "una", "con",
			"en", "por", "para", "que", "and", "the", "mis", "sus", "al", "se", "es", "como", "sobre");

	/**
	 * Como {@link #validate(List)}, y además descarta toda sugerencia cuya evidencia no aparezca realmente en el texto
	 * que se leyó (T3). La comparación tolera lo que los modelos suelen hacer con una cita: otras mayúsculas, tildes,
	 * comillas, puntuación y espacios, y un recorte o un cambio leve (basta con que al menos el 70 % de sus palabras
	 * significativas estén en el texto). Una habilidad además debe nombrarse (o aparecer por un sinónimo) en el texto:
	 * una evidencia inventada, o una habilidad que el CV no menciona, se descarta.
	 */
	public List<SuggestionDraft> validate(List<RawSuggestion> raw, String sourceText) {
		return validate(raw, sourceText, new LinkedHashMap<>());
	}

	/** Igual, y suma en {@code discards} cuántas sugerencias se descartaron por cada motivo. */
	public List<SuggestionDraft> validate(List<RawSuggestion> raw, String sourceText, Map<String, Integer> discards) {
		return run(raw, sourceText == null ? null : Searchable.of(sourceText), discards);
	}

	public List<SuggestionDraft> validate(List<RawSuggestion> raw) {
		return run(raw, null, new LinkedHashMap<>());
	}

	private static void count(Map<String, Integer> discards, String reason) {
		discards.merge(reason, 1, Integer::sum);
	}

	/** Texto de origen listo para buscar: sin tildes, mayúsculas ni puntuación. */
	private record Searchable(String normalized, Set<String> words, List<String> lines) {

		static Searchable of(String text) {
			String normalized = normalize(text);
			List<String> lines = new ArrayList<>();
			for (String line : text.split("\\R")) {
				if (!line.isBlank()) {
					lines.add(line.strip());
				}
			}
			return new Searchable(" " + normalized + " ", new HashSet<>(java.util.Arrays.asList(normalized.split(" "))),
					lines);
		}

		/** La primera línea real del texto que nombra alguno de los términos, recortada; nulo si ninguna. */
		String lineWith(List<String> terms) {
			for (String line : lines) {
				String normalizedLine = " " + normalize(line) + " ";
				for (String term : terms) {
					String needle = normalize(term);
					if (!needle.isEmpty() && normalizedLine.contains(" " + needle + " ")) {
						return line.length() > EVIDENCE_MAX ? line.substring(0, EVIDENCE_MAX).strip() : line;
					}
				}
			}
			return null;
		}

		boolean containsPhrase(String phrase) {
			String needle = normalize(phrase);
			return !needle.isEmpty() && normalized.contains(" " + needle + " ");
		}

	}

	private static String normalize(String text) {
		return Fold.of(text).replaceAll("[^\\p{L}\\p{N}+#]+", " ").strip();
	}

	private static boolean significant(String word) {
		return !STOP_WORDS.contains(word) && (word.length() >= 3 || word.codePoints().anyMatch(Character::isDigit)
				|| word.contains("+") || word.contains("#"));
	}

	private static boolean evidenceFound(String evidence, Searchable source) {
		if (source.containsPhrase(evidence)) {
			return true;
		}
		int total = 0;
		int found = 0;
		for (String word : normalize(evidence).split(" ")) {
			if (significant(word)) {
				total++;
				if (source.words().contains(word)) {
					found++;
				}
			}
		}
		return total > 0 && found * 10 >= total * 7;
	}

	private boolean skillNamed(SuggestionDraft draft, Searchable source) {
		String name = String.valueOf(draft.payload().get("name"));
		if (source.containsPhrase(name)) {
			return true;
		}
		Object slug = draft.payload().get("slug");
		if (slug != null) {
			Optional<SkillInfo> known = skillBySlug.apply(String.valueOf(slug));
			if (known.isPresent()) {
				if (source.containsPhrase(known.get().name())) {
					return true;
				}
				for (String alias : known.get().aliases()) {
					if (source.containsPhrase(alias)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Lo que un modelo deja "en blanco" ({@code ""}, {@code []}, nivel 0) cuenta como ausente, no como un dato. */
	private static RawSuggestion normalized(RawSuggestion item) {
		return new RawSuggestion(blankToNull(item.kind()), blankToNull(item.field()), blankToNull(item.slug()),
				blankToNull(item.name()), blankToNull(item.value()),
				item.level() == null || item.level() == 0 ? null : item.level(), blankToNull(item.description()),
				item.technologies() == null || item.technologies().isEmpty() ? null : item.technologies(),
				blankToNull(item.evidence()));
	}

	/** Los términos con los que el texto debería nombrar lo sugerido (para encontrar su línea real). */
	private List<String> terms(RawSuggestion item) {
		List<String> terms = new ArrayList<>();
		switch (kindOf(item.kind())) {
			case SKILL -> {
				if (item.name() != null) {
					terms.add(item.name());
				}
				Optional<SkillInfo> known = item.slug() == null ? Optional.empty() : skillBySlug.apply(item.slug().strip());
				if (known.isEmpty() && item.name() != null) {
					known = skillByText.apply(item.name().strip());
				}
				known.ifPresent(info -> {
					terms.add(info.name());
					terms.addAll(info.aliases());
				});
			}
			case ACADEMIC -> {
				if ("CYCLE".equalsIgnoreCase(item.field())) {
					terms.add("ciclo");
				}
				else if (item.value() != null) {
					terms.add(item.value());
				}
			}
			case PROJECT -> {
				if (item.name() != null) {
					terms.add(item.name());
				}
			}
		}
		return terms;
	}

	/** Un nombre que falta se toma del catálogo cuando el slug es conocido (el modelo a veces solo da el slug). */
	private RawSuggestion withName(RawSuggestion item) {
		if (item.name() != null || item.slug() == null || kindOf(item.kind()) != SuggestionKind.SKILL) {
			return item;
		}
		Optional<SkillInfo> known = skillBySlug.apply(item.slug().strip());
		return known.isEmpty() ? item : new RawSuggestion(item.kind(), item.field(), item.slug(), known.get().name(),
				item.value(), item.level(), item.description(), item.technologies(), item.evidence());
	}

	/**
	 * Si la cita falta o no está en el texto, se usa la línea REAL del CV que nombra lo sugerido: la evidencia sigue
	 * siendo un texto del CV (T3), no una frase del modelo.
	 */
	private RawSuggestion withRealEvidence(RawSuggestion item, Searchable source) {
		if (source == null || (item.evidence() != null && evidenceFound(item.evidence(), source))) {
			return item;
		}
		String line = source.lineWith(terms(item));
		return line == null ? item : new RawSuggestion(item.kind(), item.field(), item.slug(), item.name(), item.value(),
				item.level(), item.description(), item.technologies(), line);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private List<SuggestionDraft> run(List<RawSuggestion> raw, Searchable source, Map<String, Integer> discards) {
		List<SuggestionDraft> result = new ArrayList<>();
		if (raw == null) {
			return result;
		}
		Set<String> seen = new HashSet<>();
		int skills = 0;
		int projects = 0;
		for (RawSuggestion original : raw) {
			if (original == null) {
				continue;
			}
			if (result.size() >= MAX_SUGGESTIONS) {
				count(discards, R_LIMIT);
				continue;
			}
			RawSuggestion item = normalized(original);
			SuggestionKind kind = kindOf(item.kind());
			if (kind != null) {
				item = withRealEvidence(withName(item), source);
			}
			if (kind == null) {
				count(discards, R_VALUE);
				continue;
			}
			if ((kind == SuggestionKind.SKILL && skills >= MAX_SKILLS)
					|| (kind == SuggestionKind.PROJECT && projects >= MAX_PROJECTS)) {
				count(discards, R_LIMIT);
				continue;
			}
			String[] why = new String[1];
			SuggestionDraft draft = switch (kind) {
				case SKILL -> skill(item, why);
				case ACADEMIC -> academic(item, why);
				case PROJECT -> project(item, why);
			};
			if (draft == null) {
				count(discards, kind.name() + ":" + why[0]);
				continue;
			}
			if (source != null) {
				if (!evidenceFound(draft.evidence(), source)) {
					count(discards, R_EVIDENCE);
					continue;
				}
				if (draft.kind() == SuggestionKind.SKILL && !skillNamed(draft, source)) {
					count(discards, R_SUBJECT);
					continue;
				}
			}
			if (!seen.add(key(draft))) {
				count(discards, R_DUPLICATE);
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

	private static SuggestionDraft fail(String[] why, String reason) {
		why[0] = reason;
		return null;
	}

	private static boolean shapeIsWrong(SuggestionKind kind, RawSuggestion item) {
		return switch (kind) {
			case SKILL -> item.field() != null || item.value() != null || item.description() != null
					|| item.technologies() != null;
			case ACADEMIC -> item.slug() != null || item.name() != null || item.level() != null
					|| item.description() != null || item.technologies() != null;
			case PROJECT -> item.field() != null || item.slug() != null || item.value() != null || item.level() != null;
		};
	}

	// ------------------------------------------------------------------------------------------ por tipo

	private SuggestionDraft skill(RawSuggestion item, String[] why) {
		if (item.field() != null || item.value() != null || item.description() != null || item.technologies() != null) {
			return fail(why, "SHAPE");
		}
		String evidence = evidence(item.evidence());
		String name = text(item.name(), SKILL_NAME_MAX);
		if (evidence == null || name == null) {
			return fail(why, "NO_EVIDENCIA_O_NOMBRE");
		}
		if (item.level() != null && (item.level() < 1 || item.level() > 3)) {
			return fail(why, "NIVEL");
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

	private SuggestionDraft academic(RawSuggestion item, String[] why) {
		if (item.slug() != null || item.name() != null || item.level() != null || item.description() != null
				|| item.technologies() != null) {
			return fail(why, "SHAPE");
		}
		String field = item.field() == null ? "" : item.field().strip().toUpperCase(java.util.Locale.ROOT);
		String evidence = evidence(item.evidence());
		if (!FIELDS.contains(field) || evidence == null) {
			return fail(why, "CAMPO_O_EVIDENCIA");
		}
		Map<String, Object> payload = new LinkedHashMap<>();
		if ("CYCLE".equals(field)) {
			Integer cycle = parseInt(item.value());
			if (cycle == null || cycle < 1 || cycle > maxCycle) {
				return fail(why, "CICLO");
			}
			payload.put("value", cycle);
		}
		else {
			String value = text(item.value(), ACADEMIC_MAX);
			if (value == null || value.length() < 2) {
				return fail(why, "TEXTO_ESTUDIO");
			}
			payload.put("value", value);
		}
		return new SuggestionDraft(SuggestionKind.ACADEMIC, field, payload, evidence);
	}

	private SuggestionDraft project(RawSuggestion item, String[] why) {
		if (item.field() != null || item.slug() != null || item.value() != null || item.level() != null) {
			return fail(why, "SHAPE");
		}
		String evidence = evidence(item.evidence());
		String name = text(item.name(), PROJECT_NAME_MAX);
		if (evidence == null || name == null || name.length() < 2) {
			return fail(why, "NO_EVIDENCIA_O_NOMBRE");
		}
		String description = item.description() == null || item.description().isBlank() ? ""
				: text(item.description(), PROJECT_DESCRIPTION_MAX);
		if (description == null) {
			return fail(why, "DESCRIPCION");
		}
		List<String> technologies = new ArrayList<>();
		if (item.technologies() != null) {
			if (item.technologies().size() > TECHNOLOGIES_MAX) {
				return fail(why, "TECNOLOGIAS");
			}
			Set<String> seen = new HashSet<>();
			for (String technology : item.technologies()) {
				String clean = text(technology, TECHNOLOGY_MAX);
				if (clean == null) {
					return fail(why, "TECNOLOGIA");
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
