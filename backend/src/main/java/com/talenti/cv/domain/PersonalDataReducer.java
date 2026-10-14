package com.talenti.cv.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reducción de datos innecesarios del texto de un CV, ANTES de que llegue a cualquier adaptador (009-09, RF-18,
 * RN-08). Es la misma para todos los adaptadores. Elimina correos, teléfonos, números de documento (DNI),
 * fechas de nacimiento, líneas de dirección, de estado civil y de otros datos personales, y los enlaces a redes
 * sociales; conserva los enlaces a proyectos (por ejemplo GitHub). El nombre se quita "al mejor esfuerzo": la primera
 * línea si parece un nombre, y el valor de un campo "Nombre:". No garantiza anonimizar el texto libre.
 *
 * <p>Es una función pura: no registra nada y no guarda nada.
 */
public final class PersonalDataReducer {

	private static final Pattern URL = Pattern
			.compile("(?i)(?:\\bhttps?://|\\bwww\\.|\\b(?:github|gitlab|bitbucket)\\.com/)[^\\s)>\\]]+");

	private static final Pattern SOCIAL_URL = Pattern.compile(
			"(?i)(linkedin\\.|facebook\\.|fb\\.com|instagram\\.|twitter\\.|x\\.com|tiktok\\.|wa\\.me|t\\.me|wa\\.link|api\\.whatsapp)");

	private static final Pattern EMAIL = Pattern.compile("[\\p{L}\\p{N}._%+\\-]+@[\\p{L}\\p{N}\\-]+(?:\\.[\\p{L}\\p{N}\\-]+)+");

	/** Una secuencia de dígitos con separadores; luego se cuentan los dígitos (teléfono o documento). */
	private static final Pattern NUMBER_RUN = Pattern
			.compile("(?<![\\p{L}\\p{N}])[+(]?\\d[\\d\\s().\\-]{5,}\\d(?![\\p{L}\\p{N}])");

	private static final Pattern FULL_DATE = Pattern
			.compile("(?<!\\d)\\d{1,2}\\s*[/\\-.]\\s*\\d{1,2}\\s*[/\\-.]\\s*(?:19|20)\\d{2}(?!\\d)");

	private static final Pattern TEXT_DATE = Pattern
			.compile("(?i)\\b\\d{1,2}\\s+de\\s+\\p{L}+\\s+(?:de(?:l)?\\s+)?(?:19|20)\\d{2}\\b");

	/** Etiqueta de dato personal al inicio de la línea, seguida de ":" o "-" (sobre texto sin tildes). */
	private static final Pattern PERSONAL_LABEL = Pattern.compile("^\\W*(?:dni|d\\.n\\.i\\.?|documento(?: de identidad)?|"
			+ "doc\\.? ?identidad|pasaporte|carne(?: de extranjeria)?|ruc|fecha de nacimiento|f\\. ?nac\\.?|nacimiento|"
			+ "lugar de nacimiento|nacio|edad|estado civil|nacionalidad|sexo|genero|direccion|domicilio|dir\\.|"
			+ "telefono|tel\\.|telf\\.?|celular|cel\\.|movil|whatsapp|correo(?: electronico)?|e-?mail|email|"
			+ "licencia de conducir|brevete|distrito|ciudad natal)\\s*[:\\-–]");

	private static final Pattern NAME_LABEL = Pattern
			.compile("^\\W*(?:nombres?(?: y apellidos?| completos?)?|apellidos?)\\s*[:\\-–]\\s*(.*)$");

	/** Palabras que delatan estado civil o documentos, en cualquier parte de la línea. */
	private static final Pattern SENSITIVE_ANYWHERE = Pattern.compile(
			"\\b(?:dni|estado civil|soltero|soltera|casado|casada|divorciado|divorciada|viudo|viuda|conviviente|"
					+ "fecha de nacimiento|documento de identidad|pasaporte|carne de extranjeria|marital status)\\b");

	private static final Pattern STREET = Pattern.compile(
			"\\b(?:av|avenida|jr|jiron|calle|cll|psje|pasaje|urb|urbanizacion|mz|manzana|lt|lote)\\.?\\s+\\S.*\\d");

	private static final Set<String> NOT_A_NAME_WORDS = Set.of("curriculum", "vitae", "cv", "resumen", "perfil",
			"experiencia", "educacion", "formacion", "proyectos", "habilidades", "desarrollador", "developer", "ingeniero",
			"engineer", "estudiante", "analista", "programador", "practicante", "software", "web", "backend", "frontend",
			"full", "stack", "junior", "senior", "datos", "personales", "objetivo", "contacto", "tecnologias", "skills",
			"projects", "education", "experience", "profile", "summary", "de", "del", "la", "los", "las", "en", "y",
			"para", "con", "el", "instituto", "universidad", "tecsup", "senati", "idat", "cibertec", "escuela", "colegio",
			"lima", "peru");

	/** Palabras que sí pueden aparecer dentro de un nombre ("de la", "del"). */
	private static final Set<String> NAME_CONNECTORS = Set.of("de", "del", "la", "los", "las", "y", "san", "da", "di");

	private static final Pattern NAME_TOKEN = Pattern.compile("^[\\p{Lu}][\\p{L}'’.\\-]*$");

	public PersonalDataReducer() {
	}

	/** El texto sin los datos innecesarios; vacío si el texto es nulo. */
	public String reduce(String text) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String normalized = Normalizer.normalize(text, Normalizer.Form.NFC).replace("\r\n", "\n").replace('\r', '\n')
				.replace(' ', ' ');
		List<String> lines = new ArrayList<>();
		for (String raw : normalized.split("\n")) {
			String line = stripControl(raw).strip();
			if (!line.isEmpty()) {
				lines.add(line);
			}
		}
		List<String> namesFound = new ArrayList<>();
		boolean first = true;
		List<String> kept = new ArrayList<>();
		for (String line : lines) {
			if (first) {
				first = false;
				if (looksLikeName(line)) {
					namesFound.add(line);
					continue;
				}
			}
			String labeledName = labeledName(line);
			if (labeledName != null) {
				if (!labeledName.isBlank()) {
					namesFound.add(labeledName);
				}
				continue;
			}
			String cleaned = cleanLine(line);
			if (cleaned != null) {
				kept.add(cleaned);
			}
		}
		String result = String.join("\n", kept);
		for (String name : namesFound) {
			result = removeAll(result, name);
		}
		return result;
	}

	private static String stripControl(String line) {
		StringBuilder out = new StringBuilder(line.length());
		line.codePoints().forEach(cp -> {
			if (cp == '\t') {
				out.append(' ');
			}
			else if (!Character.isISOControl(cp)) {
				out.appendCodePoint(cp);
			}
		});
		return out.toString();
	}

	/** Nulo si la línea debe desaparecer; si no, la línea sin sus datos personales. */
	private static String cleanLine(String line) {
		String folded = Fold.keepingPositions(line);
		if (PERSONAL_LABEL.matcher(folded).find() || SENSITIVE_ANYWHERE.matcher(folded).find()
				|| STREET.matcher(folded).find()) {
			return null;
		}
		// Los enlaces se protegen mientras se limpia el resto: un número dentro de una URL no es un teléfono.
		List<String> urls = new ArrayList<>();
		StringBuilder protectedText = new StringBuilder();
		Matcher urlMatcher = URL.matcher(line);
		int last = 0;
		while (urlMatcher.find()) {
			protectedText.append(line, last, urlMatcher.start());
			String url = urlMatcher.group();
			if (SOCIAL_URL.matcher(url).find()) {
				protectedText.append(' ');
			}
			else {
				protectedText.append('\u0001').append(urls.size()).append('\u0002');
				urls.add(url);
			}
			last = urlMatcher.end();
		}
		protectedText.append(line.substring(last));
		String text = protectedText.toString();
		text = EMAIL.matcher(text).replaceAll(" ");
		text = FULL_DATE.matcher(text).replaceAll(" ");
		text = TEXT_DATE.matcher(text).replaceAll(" ");
		text = removeNumberRuns(text);
		for (int i = 0; i < urls.size(); i++) {
			text = text.replace("\u0001" + i + "\u0002", urls.get(i));
		}
		text = text.replaceAll("\\s{2,}", " ").strip();
		// Quedó solo puntuación o viñetas: no hay nada que conservar.
		if (text.codePoints().noneMatch(Character::isLetterOrDigit)) {
			return null;
		}
		return text;
	}

	/** Quita teléfonos y documentos: 7 a 15 dígitos seguidos (con separadores); respeta los rangos de años. */
	private static String removeNumberRuns(String text) {
		Matcher matcher = NUMBER_RUN.matcher(text);
		StringBuilder out = new StringBuilder();
		int last = 0;
		while (matcher.find()) {
			String candidate = matcher.group();
			long digits = candidate.chars().filter(Character::isDigit).count();
			if (digits >= 7 && digits <= 15 && !onlyYears(candidate)) {
				out.append(text, last, matcher.start()).append(' ');
				last = matcher.end();
			}
		}
		out.append(text.substring(last));
		return out.toString();
	}

	/** "2021 - 2024" y similares: años de estudio o de un proyecto, no un teléfono. */
	private static boolean onlyYears(String candidate) {
		String[] tokens = candidate.split("[^\\d]+");
		int count = 0;
		for (String token : tokens) {
			if (token.isEmpty()) {
				continue;
			}
			if (!token.matches("(?:19|20)\\d{2}")) {
				return false;
			}
			count++;
		}
		return count > 0;
	}

	private static String labeledName(String line) {
		Matcher matcher = NAME_LABEL.matcher(Fold.keepingPositions(line));
		if (!matcher.matches()) {
			return null;
		}
		// El valor se toma de la línea original, en las mismas posiciones.
		int start = matcher.start(1);
		return line.substring(Math.min(start, line.length())).strip();
	}

	private static boolean looksLikeName(String line) {
		if (line.length() > 60 || line.codePoints().anyMatch(Character::isDigit) || line.contains("@")
				|| line.contains(":")) {
			return false;
		}
		String[] tokens = line.strip().split("\\s+");
		if (tokens.length < 2 || tokens.length > 6) {
			return false;
		}
		int capitalised = 0;
		for (String token : tokens) {
			String folded = Fold.of(token);
			boolean connector = NAME_CONNECTORS.contains(folded);
			if (!connector && NOT_A_NAME_WORDS.contains(folded)) {
				return false;
			}
			if (!connector) {
				if (!NAME_TOKEN.matcher(token).matches()) {
					return false;
				}
				capitalised++;
			}
		}
		return capitalised >= 2;
	}

	/** Quita todas las apariciones del nombre, sin distinguir mayúsculas ni tildes. */
	private static String removeAll(String text, String name) {
		String clean = name.strip();
		if (clean.length() < 5) {
			return text;
		}
		Pattern pattern = Pattern.compile(Pattern.quote(clean), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
		String replaced = pattern.matcher(text).replaceAll(" ");
		List<String> lines = new ArrayList<>();
		for (String line : replaced.split("\n")) {
			String stripped = line.replaceAll("\\s{2,}", " ").strip();
			if (stripped.codePoints().anyMatch(Character::isLetterOrDigit)) {
				lines.add(stripped);
			}
		}
		return String.join("\n", lines);
	}

}
