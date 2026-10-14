package com.talenti.cv.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.cv.CvExtractionPort;
import com.talenti.cv.RawSuggestion;
import com.talenti.cv.domain.CvOutputValidator;
import com.talenti.cv.domain.SuggestionDraft;

/** 009-13: un adaptador de IA simulado con salida HOSTIL: tras validar, no queda nada que rompa el esquema. */
class HostileAiCvContractTests extends CvExtractionContractTests {

	@Override
	protected CvExtractionPort adapter() {
		return new SimulatedAiAdapter(SimulatedAiAdapter.Behavior.HOSTILE);
	}

	@Test
	@DisplayName("009-13: la salida hostil (campos de más, textos enormes, niveles absurdos, repetidos) se descarta")
	void t009_13_hostileOutputIsDiscarded() {
		List<RawSuggestion> raw = adapter().extract("Java", catalog);
		assertThat(raw.size()).as("la IA hostil devuelve mucho").isGreaterThan(5);

		List<SuggestionDraft> drafts = new CvOutputValidator(
				slug -> catalog.stream().filter(s -> s.slug().equals(slug)).findFirst(), text -> Optional.empty(), 10)
						.validate(raw);

		assertThat(drafts).hasSize(1);
		assertThat(drafts.get(0).payload()).containsEntry("slug", "java");
	}

}
