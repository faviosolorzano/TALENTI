package com.talenti.practice.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;

import com.talenti.practice.adapter.OpenAiRealtimeCalls;
import com.talenti.practice.adapter.RealtimeTransport;
import com.talenti.practice.adapter.SimulatedRealtimeCalls;
import com.talenti.practice.domain.RealtimeInstructions;
import com.talenti.practice.port.RealtimeCallPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Qué adaptador abre las llamadas en tiempo real (012-56). Por defecto ({@code talenti.ai.dora.realtime.enabled=false})
 * el SIMULADO: nada sale hacia ningún servicio. Encendido exige {@code talenti.ai.dora.enabled=true},
 * {@code OPENAI_API_KEY}, {@code base-url}, {@code model} y {@code voice}; si falta algo, el arranque falla diciendo qué
 * falta y sin imprimir la clave. También carga las instrucciones versionadas de la entrevistadora (012-41).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ DoraRealtimeProperties.class, DoraAiProperties.class, OpenAiAccess.class })
class DoraRealtimeConfig {

	static final String INSTRUCTIONS = "classpath:practice/realtime-interviewer-v1.json";

	@Bean
	RealtimeInstructions realtimeInstructions(ResourceLoader resources, JsonMapper mapper) throws IOException {
		JsonNode root;
		try (InputStream input = resources.getResource(INSTRUCTIONS).getInputStream()) {
			root = mapper.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
		}
		return new RealtimeInstructions(new RealtimeInstructions.Template(root.path("version").asString(),
				root.path("base").asString(), root.path("kindPractice").asString(),
				root.path("kindInterview").asString(), root.path("greetingWithName").asString(),
				root.path("greetingWithoutName").asString(), root.path("practiceNotice").asString(),
				root.path("interviewNotice").asString(), root.path("practiceComment").asString(),
				root.path("interviewComment").asString(), root.path("startGreeting").asString(),
				root.path("startResume").asString(), root.path("startResumeClosing").asString()));
	}

	static RealtimeCallPort.EventNames eventNames(DoraRealtimeProperties properties) {
		DoraRealtimeProperties.Events events = properties.events();
		return new RealtimeCallPort.EventNames(events.channel(), events.personTranscript(), events.doraTranscript(),
				events.doraTranscriptDelta(), events.speechStarted(), events.speechStopped(), events.outputAudioStarted(),
				events.outputAudioStopped(), events.functionCall(), events.responseDone(), events.itemCreate(),
				events.responseCreate());
	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "talenti.ai.dora.realtime.enabled", havingValue = "false", matchIfMissing = true)
	static class Simulated {

		@Bean
		SimulatedRealtimeCalls simulatedRealtimeCalls(DoraRealtimeProperties properties) {
			return new SimulatedRealtimeCalls(eventNames(properties));
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "talenti.ai.dora.realtime.enabled", havingValue = "true")
	static class OpenAi {

		@Bean
		OpenAiRealtimeCalls openAiRealtimeCalls(DoraRealtimeProperties properties, DoraAiProperties ai,
				OpenAiAccess access, JsonMapper mapper) {
			String missing = null;
			if (!ai.enabled()) {
				missing = "talenti.ai.dora.enabled=true (TALENTI_AI_DORA_ENABLED)";
			}
			else if (access.apiKey() == null || access.apiKey().isBlank()) {
				missing = "la variable OPENAI_API_KEY";
			}
			else if (blank(properties.baseUrl())) {
				missing = "talenti.ai.dora.realtime.base-url";
			}
			else if (blank(properties.model())) {
				missing = "talenti.ai.dora.realtime.model";
			}
			else if (blank(properties.voice())) {
				missing = "talenti.ai.dora.realtime.voice";
			}
			if (missing != null) {
				throw new IllegalStateException("DORA en tiempo real está encendida (talenti.ai.dora.realtime.enabled=true)"
						+ " pero falta " + missing + ". Complétalo en tu .env o apágala con "
						+ "TALENTI_AI_DORA_REALTIME_ENABLED=false.");
			}
			OpenAiRealtimeCalls.Settings settings = new OpenAiRealtimeCalls.Settings(strip(properties.baseUrl()),
					properties.callsPath(), properties.hangupPath(), properties.model(), properties.voice(),
					properties.transcriptionModel(), properties.transcriptionLanguage(), properties.turnDetection(),
					properties.connectTimeout(), eventNames(properties));
			return new OpenAiRealtimeCalls(settings, RealtimeTransport.http(access.apiKey()), mapper);
		}

		private static boolean blank(String value) {
			return value == null || value.isBlank();
		}

		private static String strip(String url) {
			String value = url.strip();
			return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
		}

	}

}
