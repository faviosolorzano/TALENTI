package com.talenti.cv.domain;

/** Límite de caracteres del texto que sale hacia la IA (009-30). Corta por líneas completas. */
public final class TextLimiter {

	private TextLimiter() {
	}

	/**
	 * @param text      el texto cortado (nunca más de {@code max} caracteres)
	 * @param truncated si se cortó algo
	 */
	public record Limited(String text, boolean truncated) {

		/** Contenido del CV: no se imprime en registros. */
		@Override
		public String toString() {
			return "Limited[chars=" + text.length() + ", truncated=" + truncated + "]";
		}

	}

	public static Limited limit(String text, int max) {
		if (text.length() <= max) {
			return new Limited(text, false);
		}
		StringBuilder out = new StringBuilder();
		for (String line : text.split("\n")) {
			int needed = line.length() + (out.isEmpty() ? 0 : 1);
			if (out.length() + needed > max) {
				break;
			}
			if (!out.isEmpty()) {
				out.append('\n');
			}
			out.append(line);
		}
		if (out.isEmpty()) {
			// Una sola línea más larga que el límite: se corta sin más.
			out.append(text, 0, max);
		}
		return new Limited(out.toString(), true);
	}

}
