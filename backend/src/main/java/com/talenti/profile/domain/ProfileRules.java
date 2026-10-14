package com.talenti.profile.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
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

	public static final int NAME_MAX = 60;

	public static final int EMAIL_MAX = 254;

	private static final Pattern PERSON_NAME = Pattern.compile("[\p{L}\p{M}][\p{L}\p{M} '’.-]*");

	private static final Pattern CONTACT_EMAIL = Pattern.compile(
			"[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
					+ "@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z][a-z0-9-]{0,61}[a-z0-9]");

	private static final Pattern PERU_MOBILE = Pattern.compile("9[0-9]{8}");

	public static final int ABOUT_MIN = 10;

	public static final int ABOUT_MAX = 500;

	/** El ciclo más alto que admite la columna de la base de datos (V5). */
	public static final int CYCLE_DB_MAX = 10;

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
		if (maxCycle > CYCLE_DB_MAX) {
			throw new IllegalArgumentException(
					"talenti.profile.max-cycle no puede pasar de " + CYCLE_DB_MAX + ": la base de datos no admite más");
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

		@Override
		public String toString() {
			return "SkillInput[...]";
		}

	}

	// ------------------------------------------------------------------------------------------ resultados

	public record AboutData(String firstNames, String lastNames, String contactEmail, String phone, String about) {

		@Override
		public String toString() {
			return "AboutData[...]";
		}

	}

	public record AcademicData(String career, String institute, Integer cycle) {

		@Override
		public String toString() {
			return "AcademicData[...]";
		}

	}

	public record AvailabilityData(Integer hoursPerWeek, List<WorkModality> acceptedModalities, String location) {

		@Override
		public String toString() {
			return "AvailabilityData[...]";
		}

	}

	public record ProjectData(String name, String description, List<String> technologies) {

		@Override
		public String toString() {
			return "ProjectData[...]";
		}

	}

	public record SkillData(UUID skillId, String slug, int level) {

		@Override
		public String toString() {
			return "SkillData[...]";
		}

	}

	// ------------------------------------------------------------------------------------------ secciones

	/** 006-23, 006-27, 006-30: nombres, apellidos, contacto y presentación; todo opcional. */
	public AboutData about(String prefix, String firstNames, String lastNames, String contactEmail, String phone,
			String about) {
		List<FieldViolation> violations = new ArrayList<>();
		String cleanFirst = personName(prefix + "firstNames", firstNames, violations);
		String cleanLast = personName(prefix + "lastNames", lastNames, violations);
		String cleanEmail = contactEmail(prefix + "contactEmail", contactEmail, violations);
		String cleanPhone = phone(prefix + "phone", phone, violations);
		String cleanAbout = optionalText(prefix + "about", about, ABOUT_MAX, ABOUT_MIN, true, violations);
		throwIfAny(violations);
		return new AboutData(cleanFirst, cleanLast, cleanEmail, cleanPhone, cleanAbout);
	}

	private static String personName(String field, String raw, List<FieldViolation> violations) {
		String text = optionalText(field, raw, NAME_MAX, TEXT_MIN, false, violations);
		if (text != null && !PERSON_NAME.matcher(text).matches()) {
			violations.add(FieldViolation.of(field, "invalid"));
			return null;
		}
		return text;
	}

	private static String contactEmail(String field, String raw, List<FieldViolation> violations) {
		String text = clean(raw);
		if (text.isEmpty()) {
			return null;
		}
		text = text.toLowerCase(Locale.ROOT);
		int at = text.indexOf('@');
		if (text.codePointCount(0, text.length()) > EMAIL_MAX) {
			violations.add(FieldViolation.of(field, "max-length", EMAIL_MAX));
			return null;
		}
		if (hasHiddenOrBlankOnly(text) || at < 1 || at > 64 || !CONTACT_EMAIL.matcher(text).matches()) {
			violations.add(FieldViolation.of(field, "invalid"));
			return null;
		}
		return text;
	}

	/** Celular peruano: acepta +51 y espacios; se guarda solo con los 9 dígitos. */
	private static String phone(String field, String raw, List<FieldViolation> violations) {
		String text = clean(raw);
		if (text.isEmpty()) {
			return null;
		}
		String digits = text.replace(" ", "");
		if (digits.startsWith("+51")) {
			digits = digits.substring(3);
		}
		if (hasHiddenOrBlankOnly(text) || !PERU_MOBILE.matcher(digits).matches()) {
			violations.add(FieldViolation.of(field, "invalid"));
			return null;
		}
		return digits;
	}

	/** 006-05. Los vacíos se guardan como nulos. */
	public AcademicData academic(String prefix, String career, String institute, IntInput cycle) {
		List<FieldViolation> violations = new ArrayList<>();
		String cleanCareer = optionalText(prefix + "career", career, ACADEMIC_MAX, TEXT_MIN, false, violations);
		String cleanInstitute = optionalText(prefix + "institute", institute, ACADEMIC_MAX, TEXT_MIN, false,
				violations);
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
		String cleanLocation = optionalText(prefix + "location", location, LOCATION_MAX, TEXT_MIN, false, violations);
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
	private static String optionalText(String field, String raw, int max, int min, boolean multiline,
			List<FieldViolation> violations) {
		String text = clean(raw);
		if (text.isEmpty()) {
			return null;
		}
		return checkedText(field, text, max, min, multiline, violations);
	}

	/** Forma canónica (NFC) y sin espacios en los extremos: la misma letra escrita de dos maneras es el mismo texto. */
	private static String clean(String raw) {
		return raw == null ? "" : Normalizer.normalize(raw, Normalizer.Form.NFC).strip();
	}

	/** Texto obligatorio: recortado; vacío es {@code required}. */
	private static String requiredText(String field, String raw, int max, int min, boolean multiline,
			List<FieldViolation> violations) {
		String text = clean(raw);
		if (text.isEmpty()) {
			violations.add(FieldViolation.of(field, "required"));
			return null;
		}
		return checkedText(field, text, max, min, multiline, violations);
	}

	private static String checkedText(String field, String text, int max, int min, boolean multiline,
			List<FieldViolation> violations) {
		if (hasHiddenOrBlankOnly(text)) {
			violations.add(FieldViolation.of(field, "invalid"));
			return null;
		}
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

	/**
	 * Rechaza lo que se ve vacío o engaña a la vista: espacio de ancho cero (U+200B), marcas de dirección (bidi),
	 * separadores de línea y de párrafo, U+FEFF, U+2060; o un texto que solo tiene espacios, espacios duros
	 * (U+00A0) y otros caracteres de formato. Se permiten U+200D y U+200C (emojis y alfabetos que los necesitan).
	 */
	private static boolean hasHiddenOrBlankOnly(String text) {
		return text.codePoints().anyMatch(ProfileRules::isHidden) || text.codePoints().allMatch(ProfileRules::isBlankLike);
	}

	private static boolean isHidden(int cp) {
		if (cp == 0x200B || cp == 0x200E || cp == 0x200F || cp == 0x061C || cp == 0x2060 || cp == 0xFEFF
				|| (cp >= 0x202A && cp <= 0x202E) || (cp >= 0x2066 && cp <= 0x2069)) {
			return true;
		}
		int type = Character.getType(cp);
		return type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
	}

	private static boolean isBlankLike(int cp) {
		int type = Character.getType(cp);
		return Character.isWhitespace(cp) || type == Character.FORMAT || type == Character.SPACE_SEPARATOR
				|| type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
				|| type == Character.CONTROL;
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
