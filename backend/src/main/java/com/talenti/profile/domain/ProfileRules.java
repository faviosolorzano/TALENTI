package com.talenti.profile.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.talenti.profile.WorkModality;
import com.talenti.shared.web.FieldViolation;
import com.talenti.shared.web.ValidationFailedException;

/**
 * Validación y normalización de cada sección del perfil (006-05 a 006-08). Los textos se recortan y los vacíos
 * pasan a nulos (donde el campo es opcional). Cada método junta TODAS las reglas incumplidas de la sección y, si hay
 * alguna, lanza {@link ValidationFailedException} (422) sin guardar nada. Los mensajes nunca repiten el valor
 * recibido (RNF-19). Los topes de texto son los de las columnas de la base (V5).
 *
 * <p>El parámetro {@code prefix} antepone la ruta del campo en los errores: vacío en las rutas de {@code /profile}
 * y, por ejemplo, {@code "academic."} cuando las reglas se usan desde {@code ProfileEditor}.
 */
public final class ProfileRules {

	public static final int TEXT_MIN = 2;

	public static final int ACADEMIC_MAX = 80;

	public static final int LOCATION_MAX = 60;

	public static final int HOURS_MIN = 1;

	public static final int HOURS_MAX = 40;

	public static final int CYCLE_MIN = 1;

	public static final int LEVEL_MIN = 1;

	public static final int LEVEL_MAX = 3;

	public static final int PROJECT_NAME_MAX = 80;

	public static final int PROJECT_DESCRIPTION_MIN = 10;

	public static final int PROJECT_DESCRIPTION_MAX = 500;

	public static final int TECHNOLOGIES_MAX = 8;

	public static final int TECHNOLOGY_MAX = 30;

	/** Tope de elementos de una solicitud de habilidades: protege de listas desmedidas (el catálogo tiene ~30). */
	public static final int SKILLS_PER_REQUEST_MAX = 100;

	private final int maxCycle;

	private final int maxProjects;

	public ProfileRules(int maxCycle, int maxProjects) {
		if (maxCycle < CYCLE_MIN || maxProjects < 1) {
			throw new IllegalArgumentException("talenti.profile: max-cycle y max-projects deben ser al menos 1");
		}
		this.maxCycle = maxCycle;
		this.maxProjects = maxProjects;
	}

	public int maxProjects() {
		return maxProjects;
	}

	// ------------------------------------------------------------------------------------------ entradas

	/** Una habilidad tal como llegó: el {@code slug} del catálogo y el nivel. */
	public record SkillInput(String slug, IntInput level) {
	}

	// ------------------------------------------------------------------------------------------ resultados

	public record AcademicData(String career, String institute, Integer cycle) {
	}

	public record AvailabilityData(Integer hoursPerWeek, List<WorkModality> acceptedModalities, String location) {
	}

	public record ProjectData(String name, String description, List<String> technologies) {
	}

	public record SkillData(UUID skillId, String slug, int level) {
	}

	// ------------------------------------------------------------------------------------------ secciones

	/** 006-05. Los vacíos se guardan como nulos. */
	public AcademicData academic(String prefix, String career, String institute, IntInput cycle) {
		List<FieldViolation> violations = new ArrayList<>();
		String cleanCareer = optionalText(prefix + "career", career, ACADEMIC_MAX, violations);
		String cleanInstitute = optionalText(prefix + "institute", institute, ACADEMIC_MAX, violations);
		Integer cleanCycle = optionalInt(prefix + "cycle", cycle, CYCLE_MIN, maxCycle, violations);
		throwIfAny(violations);
		return new AcademicData(cleanCareer, cleanInstitute, cleanCycle);
	}

	/**
	 * 006-06. {@code skillIdBySlug} devuelve el id de la habilidad del catálogo o nulo si no existe (el módulo
	 * consulta el catálogo solo por su interfaz pública).
	 */
	public List<SkillData> skills(String prefix, List<SkillInput> skills, Function<String, UUID> skillIdBySlug) {
		List<FieldViolation> violations = new ArrayList<>();
		if (skills == null) {
			throw new ValidationFailedException(List.of(FieldViolation.of(prefix + "skills", "required")));
		}
		if (skills.size() > SKILLS_PER_REQUEST_MAX) {
			throw new ValidationFailedException(
					List.of(FieldViolation.of(prefix + "skills", "max-items", SKILLS_PER_REQUEST_MAX)));
		}
		List<SkillData> result = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < skills.size(); i++) {
			String base = prefix + "skills[" + i + "]";
			SkillInput input = skills.get(i);
			String slug = input == null || input.slug() == null ? "" : input.slug().strip();
			UUID skillId = null;
			if (slug.isEmpty()) {
				violations.add(FieldViolation.of(base + ".slug", "required"));
			}
			else if ((skillId = skillIdBySlug.apply(slug)) == null) {
				violations.add(FieldViolation.of(base + ".slug", "unknown-skill"));
			}
			else if (!seen.add(slug)) {
				violations.add(FieldViolation.of(base + ".slug", "duplicate"));
				skillId = null;
			}
			IntInput level = input == null ? null : input.level();
			if (level == null || level.malformed() || level.value() == null || level.value() < LEVEL_MIN
					|| level.value() > LEVEL_MAX) {
				violations.add(FieldViolation.of(base + ".level", "invalid-level"));
			}
			else if (skillId != null) {
				result.add(new SkillData(skillId, slug, level.value()));
			}
		}
		throwIfAny(violations);
		return result;
	}

	/** 006-07. */
	public AvailabilityData availability(String prefix, IntInput hoursPerWeek, List<String> modalities,
			String location) {
		List<FieldViolation> violations = new ArrayList<>();
		Integer hours = optionalInt(prefix + "hoursPerWeek", hoursPerWeek, HOURS_MIN, HOURS_MAX, violations);
		List<WorkModality> accepted = new ArrayList<>();
		if (modalities != null) {
			if (modalities.size() > WorkModality.values().length * 2) {
				violations.add(FieldViolation.of(prefix + "acceptedModalities", "max-items",
						WorkModality.values().length));
			}
			else {
				for (int i = 0; i < modalities.size(); i++) {
					String field = prefix + "acceptedModalities[" + i + "]";
					WorkModality modality = parseModality(modalities.get(i));
					if (modality == null) {
						violations.add(FieldViolation.of(field, "invalid"));
					}
					else if (accepted.contains(modality)) {
						violations.add(FieldViolation.of(field, "duplicate"));
					}
					else {
						accepted.add(modality);
					}
				}
			}
		}
		String cleanLocation = optionalText(prefix + "location", location, LOCATION_MAX, violations);
		throwIfAny(violations);
		return new AvailabilityData(hours, accepted, cleanLocation);
	}

	/** 006-08. */
	public ProjectData project(String prefix, String name, String description, List<String> technologies) {
		List<FieldViolation> violations = new ArrayList<>();
		String cleanName = requiredText(prefix + "name", name, PROJECT_NAME_MAX, TEXT_MIN, false, violations);
		String cleanDescription = requiredText(prefix + "description", description, PROJECT_DESCRIPTION_MAX,
				PROJECT_DESCRIPTION_MIN, true, violations);
		List<String> cleanTechnologies = new ArrayList<>();
		String field = prefix + "technologies";
		if (technologies == null || technologies.isEmpty()) {
			violations.add(FieldViolation.of(field, "required"));
		}
		else if (technologies.size() > TECHNOLOGIES_MAX) {
			violations.add(FieldViolation.of(field, "max-items", TECHNOLOGIES_MAX));
		}
		else {
			Set<String> seen = new HashSet<>();
			for (int i = 0; i < technologies.size(); i++) {
				String itemField = field + "[" + i + "]";
				String clean = requiredText(itemField, technologies.get(i), TECHNOLOGY_MAX, 1, false, violations);
				if (clean != null) {
					if (seen.add(clean.toLowerCase(Locale.ROOT))) {
						cleanTechnologies.add(clean);
					}
					else {
						violations.add(FieldViolation.of(itemField, "duplicate"));
					}
				}
			}
		}
		throwIfAny(violations);
		return new ProjectData(cleanName, cleanDescription, cleanTechnologies);
	}

	/** 006-08: el proyecto número 11 no entra. */
	public void checkProjectLimit(String field, int currentCount, int adding) {
		if (currentCount + adding > maxProjects) {
			throw new ValidationFailedException(List.of(FieldViolation.of(field, "limit", maxProjects)));
		}
	}

	// ------------------------------------------------------------------------------------------ piezas

	private static WorkModality parseModality(String raw) {
		if (raw == null) {
			return null;
		}
		for (WorkModality modality : WorkModality.values()) {
			if (modality.name().equals(raw.strip())) {
				return modality;
			}
		}
		return null;
	}

	/** Texto opcional: recortado; vacío pasa a nulo; si hay texto, de {@link #TEXT_MIN} a {@code max} caracteres. */
	private static String optionalText(String field, String raw, int max, List<FieldViolation> violations) {
		String text = raw == null ? "" : raw.strip();
		if (text.isEmpty()) {
			return null;
		}
		return checkedText(field, text, max, TEXT_MIN, false, violations);
	}

	/** Texto obligatorio: recortado; vacío es {@code required}. */
	private static String requiredText(String field, String raw, int max, int min, boolean multiline,
			List<FieldViolation> violations) {
		String text = raw == null ? "" : raw.strip();
		if (text.isEmpty()) {
			violations.add(FieldViolation.of(field, "required"));
			return null;
		}
		return checkedText(field, text, max, min, multiline, violations);
	}

	private static String checkedText(String field, String text, int max, int min, boolean multiline,
			List<FieldViolation> violations) {
		int length = text.codePointCount(0, text.length());
		if (length < min) {
			violations.add(FieldViolation.of(field, "min-length", min));
			return null;
		}
		if (length > max) {
			violations.add(FieldViolation.of(field, "max-length", max));
			return null;
		}
		// Un carácter de control (por ejemplo NUL) no es texto y la base de datos lo rechazaría.
		boolean forbidden = text.codePoints().anyMatch(
				cp -> Character.isISOControl(cp) && !(multiline && (cp == '\n' || cp == '\r' || cp == '\t')));
		if (forbidden) {
			violations.add(FieldViolation.of(field, "invalid"));
			return null;
		}
		return text;
	}

	private static Integer optionalInt(String field, IntInput input, int min, int max,
			List<FieldViolation> violations) {
		if (input == null || (input.value() == null && !input.malformed())) {
			return null;
		}
		if (input.malformed()) {
			violations.add(FieldViolation.of(field, "invalid"));
			return null;
		}
		if (input.value() < min || input.value() > max) {
			violations.add(FieldViolation.of(field, "out-of-range", min, max));
			return null;
		}
		return input.value();
	}

	private static void throwIfAny(List<FieldViolation> violations) {
		if (!violations.isEmpty()) {
			throw new ValidationFailedException(violations);
		}
	}

}
