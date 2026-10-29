package com.talenti.practice.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.talenti.practice.adapter.OpenAiRealtimeCalls;
import com.talenti.practice.adapter.SimulatedRealtimeCalls;
import com.talenti.practice.domain.RealtimeInstructions;
import com.talenti.practice.port.RealtimeCallPort;

import tools.jackson.databind.json.JsonMapper;

/**
 * 012-56: el tiempo real está apagado por defecto (simulado); encendido, el arranque falla con un mensaje claro si
 * falta DORA encendida, la clave, la URL, el modelo o la voz, sin imprimir la clave; con todo (clave FALSA) arranca
 * sin conectarse a nada.
 */
class DoraRealtimeStartupTests {

	private static final String FAKE_KEY = "sk-clave-falsa-tiempo-real-0123456789abcdef";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withBean(JsonMapper.class, () -> JsonMapper.builder().build())
			.withUserConfiguration(DoraConfig.class, DoraAiConfig.class, DoraRealtimeConfig.class)
			.withPropertyValues("talenti.ai.openai.base-url=http://127.0.0.1:1/v1",
					"talenti.ai.dora.realtime.base-url=http://127.0.0.1:1/v1");

	private ApplicationContextRunner enabled() {
		return runner.withPropertyValues("talenti.ai.dora.enabled=true", "talenti.ai.dora.models.dialog=m",
				"talenti.ai.dora.models.stt=s", "talenti.ai.dora.models.tts=t", "talenti.ai.dora.voice=v",
				"talenti.ai.dora.realtime.enabled=true");
	}

	@Test
	@DisplayName("012-56: por defecto el tiempo real usa el simulado y las instrucciones v1")
	void t012_56_defaultIsSimulated() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(SimulatedRealtimeCalls.class).doesNotHaveBean(OpenAiRealtimeCalls.class);
			assertThat(context.getBean(DoraRealtimeProperties.class).enabled()).isFalse();
			assertThat(context.getBean(DoraRealtimeProperties.class).model()).isEqualTo("gpt-realtime-2.1");
			assertThat(context.getBean(DoraRealtimeProperties.class).voice()).isEqualTo("marin");
			assertThat(context.getBean(RealtimeInstructions.class).version()).isEqualTo("realtime-interviewer-v1");
		});
	}

	@Test
	@DisplayName("012-56: encendido sin DORA encendida, sin clave, sin URL, sin modelo o sin voz, no arranca y dice qué falta")
	void t012_56_missingPiecesFail() {
		runner.withPropertyValues("talenti.ai.dora.realtime.enabled=true").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("talenti.ai.dora.enabled=true");
		});
		enabled().run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("OPENAI_API_KEY");
		});
		for (String property : new String[] { "base-url", "model", "voice" }) {
			enabled().withPropertyValues("talenti.ai.openai.api-key=" + FAKE_KEY, "talenti.ai.dora.realtime." + property + "=")
					.run(context -> {
						assertThat(context).hasFailed();
						assertThat(context.getStartupFailure())
								.hasStackTraceContaining("talenti.ai.dora.realtime." + property);
						assertThat(stackTrace(context.getStartupFailure())).doesNotContain(FAKE_KEY);
					});
		}
	}

	@Test
	@DisplayName("012-56: encendido con clave falsa arranca con OpenAI y no se conecta a nada al arrancar")
	void t012_56_enabledStartsWithoutNetwork() {
		enabled().withPropertyValues("talenti.ai.openai.api-key=" + FAKE_KEY,
				"talenti.ai.dora.realtime.model=modelo-tiempo-real", "talenti.ai.dora.realtime.voice=voz-prueba")
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).hasSingleBean(OpenAiRealtimeCalls.class)
							.doesNotHaveBean(SimulatedRealtimeCalls.class);
					RealtimeCallPort.Info info = context.getBean(RealtimeCallPort.class).info();
					assertThat(info.adapter()).isEqualTo("realtime/openai");
					assertThat(info.model()).isEqualTo("modelo-tiempo-real");
					assertThat(info.voice()).isEqualTo("voz-prueba");
					assertThat(context.getBean(DoraRealtimeProperties.class).toString()).doesNotContain(FAKE_KEY);
				});
	}

	private static String stackTrace(Throwable failure) {
		java.io.StringWriter out = new java.io.StringWriter();
		failure.printStackTrace(new java.io.PrintWriter(out));
		return out.toString();
	}

}
