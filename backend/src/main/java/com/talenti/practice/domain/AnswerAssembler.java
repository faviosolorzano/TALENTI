package com.talenti.practice.domain;

import java.util.List;

/**
 * Arma la respuesta de una pregunta a partir de lo que dijo o escribió la persona en {@code QUESTION(p)} y
 * {@code FOLLOW_UP(p)}, en orden (012-46). Las líneas de DORA nunca entran (012-47). Código puro.
 */
public final class AnswerAssembler {

	/** Lo dicho por la persona: texto y si fue escrito. */
	public record Piece(String text, boolean typed) {

		@Override
		public String toString() {
			return "Piece[typed=" + typed + "]";
		}

	}

	/** La respuesta armada; {@code typed} si TODO fue escrito. */
	public record Answer(String text, boolean typed) {

		@Override
		public String toString() {
			return "Answer[length=" + text.length() + ", typed=" + typed + "]";
		}

	}

	private AnswerAssembler() {
	}

	/** Nulo si la persona no dijo nada: la pregunta queda "sin respuesta" (principio 4). */
	public static Answer assemble(List<Piece> pieces, int maxLength) {
		StringBuilder text = new StringBuilder();
		boolean typed = true;
		for (Piece piece : pieces) {
			String value = piece.text() == null ? "" : piece.text().strip();
			if (value.isEmpty()) {
				continue;
			}
			if (!text.isEmpty()) {
				text.append(' ');
			}
			text.append(value);
			typed &= piece.typed();
		}
		if (text.isEmpty()) {
			return null;
		}
		String value = text.length() > maxLength ? text.substring(0, maxLength) : text.toString();
		return new Answer(value, typed);
	}

}
