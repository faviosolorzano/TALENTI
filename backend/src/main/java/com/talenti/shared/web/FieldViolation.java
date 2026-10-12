package com.talenti.shared.web;

import java.util.List;

/**
 * Una regla de validación incumplida en un campo (422). {@code rule} es un código estable
 * (por ejemplo {@code min-length}); el texto en español sale de {@code messages.properties}.
 */
public record FieldViolation(String field, String rule, List<Object> args) {

	public FieldViolation {
		args = args == null ? List.of() : List.copyOf(args);
	}

	public static FieldViolation of(String field, String rule, Object... args) {
		return new FieldViolation(field, rule, List.of(args));
	}

}
