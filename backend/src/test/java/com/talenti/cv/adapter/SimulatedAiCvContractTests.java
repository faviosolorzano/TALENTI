package com.talenti.cv.adapter;

import com.talenti.cv.CvExtractionPort;

/** 009-11: un adaptador de IA simulado, con salida razonable, cumple el mismo contrato que el de reglas. */
class SimulatedAiCvContractTests extends CvExtractionContractTests {

	@Override
	protected CvExtractionPort adapter() {
		return new SimulatedAiAdapter(SimulatedAiAdapter.Behavior.WELL_BEHAVED);
	}

}
