package com.talenti.practice.adapter;

/** 012-31: la suite de contrato contra los tres adaptadores simulados. */
class SimulatedConversationContractTests extends ConversationContractTests {

	@Override
	protected Ports ports(boolean hostile) {
		SimulatedLlm llm = new SimulatedLlm();
		if (hostile) {
			llm.planMode(Simulation.Mode.HOSTILE).bridgeMode(Simulation.Mode.HOSTILE).evaluationMode(Simulation.Mode.HOSTILE);
		}
		return new Ports(llm, new SimulatedSpeechToText(), new SimulatedTextToSpeech());
	}

}
