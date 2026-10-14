package com.talenti.cv;

import java.util.List;

/**
 * Lo que un adaptador devuelve, tal cual y SIN confiar en ello: el validador ({@code CvOutputValidator}) lo contrasta
 * con el esquema fijo y las reglas de 006 antes de que llegue a la persona (009-13). Un solo registro cubre los tres
 * tipos; los campos que no corresponden al tipo van nulos (y si vienen llenos, la sugerencia se descarta).
 *
 * <ul>
 * <li>{@code SKILL}: {@code slug} (nulo si no está en el catálogo), {@code name}, {@code level} (nulo si el CV no lo
 * dice).</li>
 * <li>{@code ACADEMIC}: {@code field} ({@code CAREER}, {@code INSTITUTE} o {@code CYCLE}) y {@code value}.</li>
 * <li>{@code PROJECT}: {@code name}, {@code description} y {@code technologies}.</li>
 * </ul>
 *
 * @param kind     {@code SKILL}, {@code ACADEMIC} o {@code PROJECT}
 * @param evidence la frase del CV que respalda la sugerencia
 */
public record RawSuggestion(String kind, String field, String slug, String name, String value, Integer level,
		String description, List<String> technologies, String evidence) {

	public static RawSuggestion skill(String slug, String name, Integer level, String evidence) {
		return new RawSuggestion("SKILL", null, slug, name, null, level, null, null, evidence);
	}

	public static RawSuggestion academic(String field, String value, String evidence) {
		return new RawSuggestion("ACADEMIC", field, null, null, value, null, null, null, evidence);
	}

	public static RawSuggestion project(String name, String description, List<String> technologies, String evidence) {
		return new RawSuggestion("PROJECT", null, null, name, null, null, description, technologies, evidence);
	}

	/** Contenido del CV: no se imprime en registros. */
	@Override
	public String toString() {
		return "RawSuggestion[kind=" + kind + "]";
	}

}
