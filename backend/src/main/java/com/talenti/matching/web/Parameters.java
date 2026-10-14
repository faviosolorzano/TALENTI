package com.talenti.matching.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.util.MultiValueMap;

import com.talenti.shared.web.FieldViolation;
import com.talenti.shared.web.ValidationFailedException;

/** Lectura y validación de los parámetros de consulta de {@code /compatibility}; el 422 nunca repite el valor. */
final class Parameters {

	static final int DEFAULT_PAGE_SIZE = 9;

	static final int MAX_PAGE_SIZE = 24;

	static final int MAX_SUMMARY_IDS = 24;

	private static final Pattern UUID_FORMAT = Pattern
			.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

	private Parameters() {
	}

	record Paging(int page, int size) {
	}

	static Paging paging(MultiValueMap<String, String> query) {
		List<FieldViolation> violations = new ArrayList<>();
		int page = integer(query, "page", 1, 1, Integer.MAX_VALUE, violations);
		int size = integer(query, "size", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE, violations);
		if (!violations.isEmpty()) {
			throw new ValidationFailedException(violations);
		}
		return new Paging(page, size);
	}

	/** De 1 a 24 ids distintos (repetibles); sin ids, con más de 24 o mal formados es 422. */
	static List<UUID> vacancyIds(MultiValueMap<String, String> query) {
		List<String> raw = query.get("vacancyId");
		List<String> values = raw == null ? List.of()
				: raw.stream().filter(value -> value != null && !value.isBlank()).map(String::strip).toList();
		if (values.isEmpty()) {
			throw new ValidationFailedException(List.of(FieldViolation.of("vacancyId", "required")));
		}
		Set<UUID> ids = new LinkedHashSet<>();
		for (String value : values) {
			if (!UUID_FORMAT.matcher(value).matches()) {
				throw new ValidationFailedException(List.of(FieldViolation.of("vacancyId", "invalid")));
			}
			ids.add(UUID.fromString(value));
		}
		if (ids.size() > MAX_SUMMARY_IDS) {
			throw new ValidationFailedException(
					List.of(FieldViolation.of("vacancyId", "max-items", MAX_SUMMARY_IDS)));
		}
		return List.copyOf(ids);
	}

	private static int integer(MultiValueMap<String, String> query, String name, int defaultValue, int min, int max,
			List<FieldViolation> violations) {
		List<String> values = query.get(name);
		String raw = values == null ? null
				: values.stream().filter(value -> value != null && !value.isBlank()).findFirst().orElse(null);
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

}
