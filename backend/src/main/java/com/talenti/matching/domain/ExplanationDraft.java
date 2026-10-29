package com.talenti.matching.domain;

import java.util.List;

/**
 * Lo que propone la IA (o el simulado), AÚN SIN VALIDAR: puede venir hostil, incompleto o fuera de esquema (015-05 a
 * 015-08). Solo {@link ExplanationValidator} lo convierte en {@link ExplanationContent}. Nunca se imprime.
 */
public record ExplanationDraft(String fit, String gaps, String unknown, List<Practice> practice) {

	public ExplanationDraft {
		practice = practice == null ? null : List.copyOf(practice);
	}

	public record Practice(String skillSlug, List<String> actions) {

		public Practice {
			actions = actions == null ? null : List.copyOf(actions);
		}

		@Override
		public String toString() {
			return "Practice[...]";
		}

	}

	@Override
	public String toString() {
		return "ExplanationDraft[...]";
	}

}
