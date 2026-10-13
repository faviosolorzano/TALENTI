package com.talenti.profile.web;

import java.util.List;

import com.talenti.profile.domain.IntInput;

import tools.jackson.databind.JsonNode;

/**
 * Cuerpos de las escrituras de {@code /profile}. Los números llegan como {@link JsonNode} para poder distinguir un
 * entero de un texto o un decimal y responder 422 (en vez de truncar 3.7 a 3). Los textos se validan y normalizan
 * en el dominio. El {@code toString} no imprime contenido: es información de la persona (006-12).
 */
final class Requests {

	private Requests() {
	}

	/** Entero estricto: nulo si no vino o vino {@code null}; "mal formado" si no es un entero que quepa. */
	static IntInput toInt(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return IntInput.absent();
		}
		if (node.isIntegralNumber() && node.canConvertToInt()) {
			return IntInput.of(node.intValue());
		}
		return IntInput.notAnInteger();
	}

	record Academic(String career, String institute, JsonNode cycle) {

		@Override
		public String toString() {
			return "Academic[...]";
		}

	}

	record SkillItem(String slug, JsonNode level) {

		@Override
		public String toString() {
			return "SkillItem[...]";
		}

	}

	record Skills(List<SkillItem> skills) {

		@Override
		public String toString() {
			return "Skills[...]";
		}

	}

	record Availability(JsonNode hoursPerWeek, List<String> acceptedModalities, String location) {

		@Override
		public String toString() {
			return "Availability[...]";
		}

	}

	record Project(String name, String description, List<String> technologies) {

		@Override
		public String toString() {
			return "Project[...]";
		}

	}

}
