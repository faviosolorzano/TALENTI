package com.talenti.matching.domain;

import java.util.List;

/**
 * Una explicación lista para mostrar (sección 6): ya validada si la redactó la IA, o armada por la plantilla. Los
 * textos van tuteando y en español; {@code gaps} y {@code unknown} son nulos si no hay nada que decir.
 *
 * @param practice una entrada por brecha prioritaria, en su orden (015-09), con 1 a 3 acciones
 */
public record ExplanationContent(String fit, String gaps, String unknown, List<Practice> practice) {

	public ExplanationContent {
		practice = List.copyOf(practice);
	}

	public record Practice(String skillSlug, List<String> actions) {

		public Practice {
			actions = List.copyOf(actions);
		}

		@Override
		public String toString() {
			return "Practice[" + skillSlug + "]";
		}

	}

	@Override
	public String toString() {
		return "ExplanationContent[practice=" + practice.size() + "]";
	}

}
