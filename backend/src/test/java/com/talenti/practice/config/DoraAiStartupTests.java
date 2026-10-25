package com.talenti.practice.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.talenti.practice.adapter.OpenAiLlm;
import com.talenti.practice.adapter.SimulatedLlm;
import com.talenti.practice.port.ConversationPort;

import tools.jackson.databind.json.JsonMapper;

/**
 * 012-31: DORA real está apagada por defecto (simulados); encendida sin {@code OPENAI_API_KEY} o sin modelos, el
 * arranque falla diciendo qué falta y sin imprimir la clave; con todo (clave FALSA) arranca sin conectarse a nada.
 */
class DoraAiStartupTests {

	private static final String FAKE_KEY = "sk-clave-falsa-solo-para-pruebas-dora-0123456789";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withBean(JsonMapper.class, () -> JsonMapper.builder().build())
			.withUserConfiguration(DoraConfig.class, DoraAiConfig.class)
			.withPropertyValues("talenti.ai.openai.base-url=http://127.0.0.1:1/v1");

	@Test
	@DisplayName("012-31: por defecto usa los adaptadores simulados")
	void t012_31_defaultIsSimulated() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(SimulatedLlm.class).doesNotHaveBean(OpenAiLlm.class);
			assertThat(context.getBean(ConversationPort.class).info().adapter()).isEqualTo("turn-based/simulated");
		});
	}

	@Test
	@DisplayName("012-31: encendida sin clave no arranca y lo dice; sin modelo tampoco; nunca imprime la clave")
	void t012_31_missingKeyOrModelFails() {
		runner.withPropertyValues("talenti.ai.dora.enabled=true", "talenti.ai.dora.models.dialog=m",
				"talenti.ai.dora.models.stt=s", "talenti.ai.dora.models.tts=t", "talenti.ai.dora.voice=v").run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining("OPENAI_API_KEY");
				});
		runner.withPropertyValues("talenti.ai.dora.enabled=true", "talenti.ai.openai.api-key=" + FAKE_KEY,
				"talenti.ai.dora.models.stt=s", "talenti.ai.dora.models.tts=t", "talenti.ai.dora.voice=v").run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining("talenti.ai.dora.models.dialog");
					assertThat(String.valueOf(context.getStartupFailure().getMessage())).doesNotContain(FAKE_KEY);
				});
	}

	@Test
	@DisplayName("012-31: encendida con clave falsa y modelos arranca con OpenAI y no se conecta a nada al arrancar")
	void t012_31_enabledStartsWithoutNetwork() {
		runner.withPropertyValues("talenti.ai.dora.enabled=true", "talenti.ai.openai.api-key=" + FAKE_KEY,
				"talenti.ai.dora.models.dialog=modelo-de-prueba", "talenti.ai.dora.models.stt=stt-de-prueba",
				"talenti.ai.dora.models.tts=tts-de-prueba", "talenti.ai.dora.voice=voz-de-prueba").run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(OpenAiLlm.class).doesNotHaveBean(SimulatedLlm.class);
					ConversationPort.Info info = context.getBean(ConversationPort.class).info();
					assertThat(info.adapter()).isEqualTo("turn-based/openai");
					assertThat(info.llmModel()).isEqualTo("modelo-de-prueba");
					assertThat(context.getBean(OpenAiAccess.class).toString()).doesNotContain(FAKE_KEY);
				});
	}

}
