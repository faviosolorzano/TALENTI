package com.talenti.cv.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.talenti.cv.EnhancedCvExtractionPort;
import com.talenti.cv.adapter.OpenAiCvExtractionAdapter;

import tools.jackson.databind.json.JsonMapper;

/**
 * 009-11: el camino para probar la IA real. Con la IA encendida (openai + enabled + modelo + clave FALSA) el backend
 * arranca y NO llama a la red hasta que se usa; sin clave o sin modelo no arranca y el mensaje no trae la clave.
 * La dirección apunta a un puerto cerrado: si algo intentara conectarse al arrancar, esta prueba fallaría.
 */
class CvOpenAiStartupTests {

	private static final String FAKE_KEY = "sk-clave-falsa-solo-para-pruebas-0123456789";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withBean(Clock.class, Clock::systemUTC).withBean(JsonMapper.class, () -> JsonMapper.builder().build())
			.withUserConfiguration(CvConfig.class)
			.withPropertyValues("talenti.ai.openai.base-url=http://127.0.0.1:1/v1");

	@Test
	@DisplayName("009-11: openai + enabled + modelo + clave falsa arranca, crea el adaptador y no se conecta a nada")
	void t009_11_startsWithoutCallingTheNetwork() {
		runner.withPropertyValues("talenti.ai.provider=openai", "talenti.ai.cv.enabled=true",
				"talenti.ai.cv.model=modelo-de-prueba", "talenti.ai.openai.api-key=" + FAKE_KEY).run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(OpenAiCvExtractionAdapter.class);
					assertThat(context.getBean(EnhancedCvExtractionPort.class).info().model())
							.isEqualTo("modelo-de-prueba");
					assertThat(context.getBean(AiCvProperties.class).toString()).doesNotContain(FAKE_KEY);
				});
	}

	@Test
	@DisplayName("009-11: encendida sin clave o sin modelo no arranca, dice qué falta y nunca imprime la clave")
	void t009_11_missingKeyOrModelFailsClearly() {
		runner.withPropertyValues("talenti.ai.provider=openai", "talenti.ai.cv.enabled=true",
				"talenti.ai.cv.model=modelo-de-prueba").run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining("OPENAI_API_KEY");
				});
		runner.withPropertyValues("talenti.ai.provider=openai", "talenti.ai.cv.enabled=true",
				"talenti.ai.openai.api-key=" + FAKE_KEY).run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).hasStackTraceContaining("talenti.ai.cv.model");
					assertThat(String.valueOf(context.getStartupFailure().getMessage())).doesNotContain(FAKE_KEY);
				});
	}

	@Test
	@DisplayName("009-11: encendida con AI_PROVIDER=simulated no arranca y el mensaje dice qué poner")
	void t009_11_simulatedProviderCannotBeEnabled() {
		runner.withPropertyValues("talenti.ai.cv.enabled=true").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("AI_PROVIDER=openai");
		});
	}

	@Test
	@DisplayName("009-11: por defecto (apagada) arranca sin adaptador de IA")
	void t009_11_defaultsAreOff() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(OpenAiCvExtractionAdapter.class);
		});
	}

}
