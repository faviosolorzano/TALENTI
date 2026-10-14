package com.talenti.cv.adapter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.talenti.cv.AdapterInfo;
import com.talenti.cv.CvExtractionPort;
import com.talenti.cv.RawSuggestion;
import com.talenti.cv.domain.CareerDictionary;
import com.talenti.cv.domain.Fold;
import com.talenti.cv.domain.SkillMatcher;
import com.talenti.vacancy.SkillInfo;

/**
 * Lector de CV por reglas (009-12): local, sin IA ni red, determinista (el mismo texto da las mismas sugerencias). Es
 * una BÚSQUEDA, no una comprensión:
 * <ul>
 * <li>Habilidades: cada habilidad del catálogo cuyo nombre o sinónimo aparece como palabra completa; la evidencia es
 * la línea donde aparece y no lleva nivel (el CV no lo dice: lo elige la persona).</li>
 * <li>Estudios: la carrera por el diccionario, el instituto por la línea con "Instituto", "Universidad", "Tecsup",
 * "Senati"..., y el ciclo por patrones como "5.º ciclo" o "ciclo 5" (de 1 a 10).</li>
 * <li>Proyectos: los bloques bajo un encabezado "Proyectos", con nombre, descripción y las tecnologías del catálogo
 * que mencionan.</li>
 * </ul>
 * Sin estado y sin registros: nunca escribe contenido del CV en ningún lado.
 */
public class RulesCvExtractionAdapter implements CvExtractionPort {

	public static final String NAME = "rules";

	public static final String VERSION = "1.0.0";

	public static final String RULE_SET_VERSION = "2026.10-1";

	private static final int EVIDENCE_MAX = 200;

	private static final Set<String> EDUCATION_HEADINGS = Set.of("educacion", "formacion", "formacion academica",
			"estudios", "estudios realizados", "educacion y formacion", "formacion y estudios", "education",
			"academic background");

	private static final Set<String> PROJECT_HEADINGS = Set.of("proyectos", "proyectos personales",
			"proyectos academicos", "proyectos destacados", "proyectos realizados", "proyectos y logros", "projects",
			"personal projects", "portafolio");

	private static final Set<String> OTHER_HEADINGS = Set.of("experiencia", "experiencia laboral",
			"experiencia profesional", "habilidades", "habilidades tecnicas", "competencias", "skills", "tecnologias",
			"conocimientos", "idiomas", "certificaciones", "cursos", "referencias", "datos personales", "perfil",
			"perfil profesional", "resumen", "objetivo", "objetivos", "logros", "intereses", "voluntariado",
			"work experience", "languages", "summary", "profile", "certifications", "courses", "contacto");

	private static final Pattern INSTITUTE_WORD = Pattern.compile(
			"(?<![\\p{L}\\p{N}])(?:instituto|universidad|tecsup|senati|idat|cibertec|tecnologico|institute|university)"
					+ "(?![\\p{L}\\p{N}])");

	private static final Pattern SEGMENT_BREAK = Pattern
			.compile("\\s[-\u2013\u2014|\u00B7\u2022/]\\s|[,;()\\[\\]:]|\\s(?=(?:19|20)\\d{2}\\b)");

	private static final Pattern CYCLE_NUMBER_BEFORE = Pattern.compile(
			"(?<!\\d)(\\d{1,2})\\s*(?:\\.\\s*)?(?:[\u00BA\u00B0]|o\\b|er\\b|ro\\b|do\\b|to\\b|vo\\b|mo\\b|no\\b|avo\\b)?"
					+ "\\s*\\.?\\s*ciclo\\b");

	private static final Pattern CYCLE_NUMBER_AFTER = Pattern.compile("ciclo\\s*(?:actual\\s*)?[:\\-]?\\s*(\\d{1,2})\\b");

	private static final Map<String, Integer> ORDINALS = Map.ofEntries(Map.entry("primer", 1), Map.entry("primero", 1),
			Map.entry("segundo", 2), Map.entry("tercer", 3), Map.entry("tercero", 3), Map.entry("cuarto", 4),
			Map.entry("quinto", 5), Map.entry("sexto", 6), Map.entry("septimo", 7), Map.entry("octavo", 8),
			Map.entry("noveno", 9), Map.entry("decimo", 10));

	private static final Pattern CYCLE_ORDINAL = Pattern.compile(
			"(?<![\\p{L}])(primer|primero|segundo|tercer|tercero|cuarto|quinto|sexto|septimo|octavo|noveno|decimo)\\s+ciclo\\b"
					+ "|ciclo\\s+(primero|segundo|tercero|cuarto|quinto|sexto|septimo|octavo|noveno|decimo)\\b");

	private static final Pattern BULLET = Pattern.compile("^[-\u2022*\u00B7\u25AA\u25E6\u2023\u2013\u2014]+\\s*");

	private static final Pattern NAME_AND_DESCRIPTION = Pattern
			.compile("^(.{2,60}?)\\s*(?::|\\s[-\u2013\u2014]\\s)\\s*(.{10,})$");

	private static final Pattern SEPARATOR = Pattern.compile(":|\\s[-\u2013\u2014]\\s");

	private enum Section {
		NONE, EDUCATION, PROJECTS, OTHER
	}

	private record Line(String text, Section section, boolean heading) {
	}

	private final CareerDictionary careers;

	public RulesCvExtractionAdapter(CareerDictionary careers) {
		this.careers = careers;
	}

	@Override
	public AdapterInfo info() {
		return new AdapterInfo(NAME, VERSION, RULE_SET_VERSION, null);
	}

	@Override
	public List<RawSuggestion> extract(String reducedText, List<SkillInfo> catalog) {
		List<Line> lines = parse(reducedText == null ? "" : reducedText);
		SkillMatcher matcher = new SkillMatcher(catalog);
		List<RawSuggestion> result = new ArrayList<>();
		academic(lines, result);
		skills(lines, matcher, result);
		projects(lines, matcher, result);
		return result;
	}

	// ------------------------------------------------------------------------------------------ secciones

	private static List<Line> parse(String text) {
		List<Line> lines = new ArrayList<>();
		Section current = Section.NONE;
		for (String raw : text.split("\n")) {
			String line = raw.strip();
			if (line.isEmpty()) {
				continue;
			}
			Section heading = headingOf(line);
			if (heading != null) {
				current = heading;
				lines.add(new Line(line, current, true));
			}
			else {
				lines.add(new Line(line, current, false));
			}
		}
		return lines;
	}

	private static Section headingOf(String line) {
		if (line.length() > 40) {
			return null;
		}
		String folded = Fold.of(line.replaceAll("^[^\\p{L}]+|[^\\p{L}]+$", ""));
		if (EDUCATION_HEADINGS.contains(folded)) {
			return Section.EDUCATION;
		}
		if (PROJECT_HEADINGS.contains(folded)) {
			return Section.PROJECTS;
		}
		if (OTHER_HEADINGS.contains(folded)) {
			return Section.OTHER;
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------ estudios

	private void academic(List<Line> lines, List<RawSuggestion> out) {
		findFirst(lines, line -> careers.find(line.text()).map(career -> RawSuggestion.academic("CAREER", career,
				cut(line.text(), EVIDENCE_MAX)))).ifPresent(out::add);
		findFirst(lines, line -> institute(line.text()).map(value -> RawSuggestion.academic("INSTITUTE", value,
				cut(line.text(), EVIDENCE_MAX)))).ifPresent(out::add);
		findFirst(lines, line -> cycle(line.text()).map(value -> RawSuggestion.academic("CYCLE",
				Integer.toString(value), cut(line.text(), EVIDENCE_MAX)))).ifPresent(out::add);
	}

	/** Primero en la sección de estudios; si no hay nada ahí, en todo el texto. */
	private static Optional<RawSuggestion> findFirst(List<Line> lines,
			java.util.function.Function<Line, Optional<RawSuggestion>> finder) {
		for (boolean onlyEducation : new boolean[] { true, false }) {
			for (Line line : lines) {
				if (line.heading() || (onlyEducation && line.section() != Section.EDUCATION)) {
					continue;
				}
				Optional<RawSuggestion> found = finder.apply(line);
				if (found.isPresent()) {
					return found;
				}
			}
		}
		return Optional.empty();
	}

	/** El tramo de la línea que contiene la palabra de institución ("Tecsup", "Instituto ...", "Universidad ..."). */
	static Optional<String> institute(String line) {
		String folded = Fold.keepingPositions(line);
		Matcher word = INSTITUTE_WORD.matcher(folded);
		if (!word.find()) {
			return Optional.empty();
		}
		int from = 0;
		int to = line.length();
		Matcher breaks = SEGMENT_BREAK.matcher(line);
		while (breaks.find()) {
			if (breaks.end() <= word.start()) {
				from = breaks.end();
			}
			else if (breaks.start() >= word.end()) {
				to = breaks.start();
				break;
			}
		}
		String value = line.substring(from, Math.max(from, to)).strip();
		if (value.length() < 2 || value.length() > 80) {
			return Optional.empty();
		}
		return Optional.of(value);
	}

	/** El ciclo (1 a 10) de patrones como "5.º ciclo", "5to ciclo", "ciclo 5" o "quinto ciclo". */
	static Optional<Integer> cycle(String line) {
		String folded = Fold.keepingPositions(line);
		for (Pattern pattern : List.of(CYCLE_NUMBER_BEFORE, CYCLE_NUMBER_AFTER)) {
			Matcher matcher = pattern.matcher(folded);
			while (matcher.find()) {
				int value = Integer.parseInt(matcher.group(1));
				if (value >= 1 && value <= 10) {
					return Optional.of(value);
				}
			}
		}
		Matcher ordinal = CYCLE_ORDINAL.matcher(folded);
		if (ordinal.find()) {
			String word = ordinal.group(1) != null ? ordinal.group(1) : ordinal.group(2);
			return Optional.ofNullable(ORDINALS.get(word));
		}
		return Optional.empty();
	}

	// ------------------------------------------------------------------------------------------ habilidades

	private static void skills(List<Line> lines, SkillMatcher matcher, List<RawSuggestion> out) {
		Set<String> emitted = new HashSet<>();
		for (Line line : lines) {
			if (line.heading()) {
				continue;
			}
			for (SkillInfo skill : matcher.find(line.text())) {
				if (emitted.add(skill.slug())) {
					out.add(RawSuggestion.skill(skill.slug(), skill.name(), null, cut(line.text(), EVIDENCE_MAX)));
				}
			}
		}
	}

	// ------------------------------------------------------------------------------------------ proyectos

	private static final class Block {

		String name;

		final StringBuilder description = new StringBuilder();

		final String evidence;

		final StringBuilder everything = new StringBuilder();

		Block(String name, String evidence) {
			this.name = name;
			this.evidence = evidence;
			this.everything.append(name);
		}

		void describe(String text) {
			if (!description.isEmpty()) {
				description.append(' ');
			}
			description.append(text);
			everything.append('\n').append(text);
		}

	}

	private static void projects(List<Line> lines, SkillMatcher matcher, List<RawSuggestion> out) {
		List<Block> blocks = new ArrayList<>();
		Block current = null;
		for (Line line : lines) {
			if (line.heading() || line.section() != Section.PROJECTS) {
				if (line.section() != Section.PROJECTS) {
					current = null;
				}
				continue;
			}
			Matcher bullet = BULLET.matcher(line.text());
			boolean isBullet = bullet.find();
			String text = isBullet ? line.text().substring(bullet.end()).strip() : line.text();
			if (text.isEmpty()) {
				continue;
			}
			if (!isBullet && isTitleLike(text)) {
				current = new Block(text, line.text());
				blocks.add(current);
				continue;
			}
			if (current != null && isBullet) {
				current.describe(text);
				continue;
			}
			Matcher split = NAME_AND_DESCRIPTION.matcher(text);
			if (split.matches()) {
				current = new Block(split.group(1).strip(), line.text());
				current.describe(split.group(2).strip());
				blocks.add(current);
			}
			else if (current != null) {
				current.describe(text);
			}
			else {
				current = new Block(cutAtWord(text, 60), line.text());
				current.describe(text);
				blocks.add(current);
			}
		}
		for (Block block : blocks) {
			List<String> technologies = new ArrayList<>();
			for (SkillInfo skill : matcher.find(block.everything.toString())) {
				if (technologies.size() < 8) {
					technologies.add(skill.name());
				}
			}
			String description = cutAtWord(block.description.toString(), 500);
			if (description.isEmpty() && technologies.isEmpty()) {
				continue;
			}
			out.add(RawSuggestion.project(cutAtWord(block.name, 80), description, technologies,
					cut(block.evidence, EVIDENCE_MAX)));
		}
	}

	private static boolean isTitleLike(String text) {
		if (text.length() > 60 || text.endsWith(".") || SEPARATOR.matcher(text).find()) {
			return false;
		}
		if (text.split("\\s+").length > 8) {
			return false;
		}
		int first = text.codePointAt(0);
		return Character.isUpperCase(first) || Character.isDigit(first);
	}

	// ------------------------------------------------------------------------------------------ piezas

	private static String cut(String text, int max) {
		return text.length() <= max ? text : text.substring(0, max).strip();
	}

	private static String cutAtWord(String text, int max) {
		String stripped = text.strip();
		if (stripped.length() <= max) {
			return stripped;
		}
		int space = stripped.lastIndexOf(' ', max);
		return stripped.substring(0, space > max / 2 ? space : max).strip();
	}

}
