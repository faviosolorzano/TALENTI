package com.talenti.vacancy.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.util.MultiValueMap;

import com.talenti.shared.web.FieldViolation;
import com.talenti.shared.web.ValidationFailedException;
import com.talenti.vacancy.domain.Modality;
import com.talenti.vacancy.domain.SearchCriteria;
import com.talenti.vacancy.domain.SearchSort;
import com.talenti.vacancy.domain.SearchText;
import com.talenti.vacancy.domain.TextNormalizer;

/**
 * Valida y normaliza los parámetros de {@code GET /vacancies} (004-06 a 004-10). Todo lo que no cumple se reúne en
 * un solo 422 con {@code errors[{field, rule}]} y la búsqueda no se ejecuta. Los parámetros repetidos se
 * deduplican; un parámetro vacío cuenta como ausente. Los mensajes de error nunca repiten el valor recibido: el
 * texto buscado no viaja a los registros.
 */
final class SearchParameters {

	static final int DEFAULT_PAGE_SIZE = 9;

	static final int MAX_PAGE_SIZE = 24;

	static final int MAX_FILTER_VALUES = 10;

	static final int MAX_LOCATION_LENGTH = 60;

	static final int MAX_SKILL_LENGTH = 40;

	private static final Pattern SLUG = Pattern.compile("[a-z0-9-]{1," + MAX_SKILL_LENGTH + "}");

	private final SearchCriteria criteria;

	private final int page;

	private final int size;

	private SearchParameters(SearchCriteria criteria, int page, int size) {
		this.criteria = criteria;
		this.page = page;
		this.size = size;
	}

	SearchCriteria criteria() {
		return criteria;
	}

	int page() {
		return page;
	}

	int size() {
		return size;
	}

	static SearchParameters parse(MultiValueMap<String, String> query) {
		List<FieldViolation> violations = new ArrayList<>();

		List<String> terms = List.of();
		String q = first(query, "q");
		if (q != null) {
			String cleaned = SearchText.clean(q);
			if (cleaned.codePointCount(0, cleaned.length()) > SearchText.MAX_LENGTH) {
				violations.add(FieldViolation.of("q", "too-long", SearchText.MAX_LENGTH));
			}
			else {
				terms = SearchText.terms(cleaned);
				if (terms.size() > SearchText.MAX_TERMS) {
					violations.add(FieldViolation.of("q", "too-many-terms", SearchText.MAX_TERMS));
					terms = List.of();
				}
			}
		}

		List<Modality> modalities = new ArrayList<>();
		List<String> modalityValues = distinct(query, "modality");
		for (String value : modalityValues) {
			Modality.parse(value).ifPresentOrElse(modalities::add,
					() -> addOnce(violations, FieldViolation.of("modality", "invalid")));
		}
		checkCount(violations, "modality", modalityValues);

		Map<String, String> uniqueLocations = new LinkedHashMap<>();
		for (String value : values(query, "location")) {
			String cleaned = SearchText.clean(value);
			if (cleaned.isEmpty()) {
				continue;
			}
			if (cleaned.codePointCount(0, cleaned.length()) > MAX_LOCATION_LENGTH || !cleaned.equals(value.strip())) {
				// Más largo que cualquier ciudad, o con caracteres de control o espacios raros: mal formado.
				addOnce(violations, FieldViolation.of("location", "invalid"));
				continue;
			}
			uniqueLocations.putIfAbsent(TextNormalizer.normalize(cleaned), cleaned);
		}
		List<String> locations = new ArrayList<>(uniqueLocations.values());
		checkCount(violations, "location", locations);

		List<String> skills = distinct(query, "skill");
		for (String value : skills) {
			if (!SLUG.matcher(value).matches()) {
				addOnce(violations, FieldViolation.of("skill", "invalid"));
			}
		}
		checkCount(violations, "skill", skills);

		SearchSort sort = SearchSort.RECENT;
		String sortValue = first(query, "sort");
		if (sortValue != null) {
			SearchSort parsed = SearchSort.parse(sortValue.strip()).orElse(null);
			if (parsed == null) {
				violations.add(FieldViolation.of("sort", "invalid"));
			}
			else {
				sort = parsed;
			}
		}

		int page = integer(query, "page", 1, 1, Integer.MAX_VALUE, violations);
		int size = integer(query, "size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE, violations);

		if (!violations.isEmpty()) {
			throw new ValidationFailedException(violations);
		}
		return new SearchParameters(new SearchCriteria(terms, modalities, locations, skills, sort), page, size);
	}

	/** {@code limit} de las rutas de destacadas (004-12): entero de 1 al máximo, por defecto {@code defaultValue}. */
	static int limit(MultiValueMap<String, String> query, int defaultValue, int max) {
		List<FieldViolation> violations = new ArrayList<>();
		int limit = integer(query, "limit", defaultValue, 1, max, violations);
		if (!violations.isEmpty()) {
			throw new ValidationFailedException(violations);
		}
		return limit;
	}

	private static int integer(MultiValueMap<String, String> query, String name, int defaultValue, int min, int max,
			List<FieldViolation> violations) {
		String raw = first(query, name);
		if (raw == null) {
			return defaultValue;
		}
		long value;
		try {
			value = Long.parseLong(raw.strip());
		}
		catch (NumberFormatException e) {
			violations.add(FieldViolation.of(name, "invalid"));
			return defaultValue;
		}
		if (value < min || value > max) {
			violations.add(FieldViolation.of(name, "out-of-range", min, max));
			return defaultValue;
		}
		return (int) value;
	}

	private static void checkCount(List<FieldViolation> violations, String field, List<?> values) {
		if (values.size() > MAX_FILTER_VALUES) {
			violations.add(FieldViolation.of(field, "too-many", MAX_FILTER_VALUES));
		}
	}

	private static void addOnce(List<FieldViolation> violations, FieldViolation violation) {
		if (!violations.contains(violation)) {
			violations.add(violation);
		}
	}

	/** El primer valor no vacío del parámetro, o {@code null} si no vino o venía vacío. */
	private static String first(MultiValueMap<String, String> query, String name) {
		List<String> values = values(query, name);
		return values.isEmpty() ? null : values.get(0);
	}

	/** Valores del parámetro, sin los vacíos. */
	private static List<String> values(MultiValueMap<String, String> query, String name) {
		List<String> raw = query.get(name);
		if (raw == null) {
			return List.of();
		}
		return raw.stream().filter(value -> value != null && !value.isBlank()).toList();
	}

	/** Valores del parámetro recortados y sin repetir, en su orden. */
	private static List<String> distinct(MultiValueMap<String, String> query, String name) {
		return values(query, name).stream().map(String::strip).distinct().toList();
	}

}
