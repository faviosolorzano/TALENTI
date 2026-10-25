package com.talenti.practice.config;

import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.talenti.practice.adapter.OpenAiLlm;
import com.talenti.practice.adapter.OpenAiSpeechToText;
import com.talenti.practice.adapter.OpenAiTextToSpeech;
import com.talenti.practice.adapter.OpenAiTransport;
import com.talenti.practice.adapter.SimulatedLlm;
import com.talenti.practice.adapter.SimulatedSpeechToText;
import com.talenti.practice.adapter.SimulatedTextToSpeech;
import com.talenti.practice.adapter.TurnBasedConversation;
import com.talenti.practice.port.ConversationPort;
import com.talenti.practice.port.LlmPort;
import com.talenti.practice.port.SpeechToTextPort;
import com.talenti.practice.port.TextToSpeechPort;

import tools.jackson.databind.json.JsonMapper;

/**
 * Qué adaptadores usa DORA (012-31). Por defecto ({@code talenti.ai.dora.enabled=false}) los tres simulados: nada sale
 * hacia ningún servicio externo. Con {@code enabled=true}, los de OpenAI: sin {@code OPENAI_API_KEY} o sin algún
 * modelo, el arranque falla con un mensaje que dice qué falta (y nunca imprime la clave).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ DoraProperties.class, DoraAiProperties.class, OpenAiAccess.class })
class DoraAiConfig {

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "talenti.ai.dora.enabled", havingValue = "false", matchIfMissing = true)
	static class Simulated {

		@Bean
		SimulatedLlm simulatedLlm() {
			return new SimulatedLlm();
		}

		@Bean
		SimulatedSpeechToText simulatedSpeechToText() {
			return new SimulatedSpeechToText();
		}

		@Bean
		SimulatedTextToSpeech simulatedTextToSpeech() {
			return new SimulatedTextToSpeech();
		}

	}

	@Configuration(proxyBeanMethods = false)
	@ConditionalOnProperty(name = "talenti.ai.dora.enabled", havingValue = "true")
	static class OpenAi {

		@Bean
		OpenAiTransport doraOpenAiTransport(OpenAiAccess access) {
			if (access.apiKey() == null || access.apiKey().isBlank()) {
				throw new IllegalStateException("DORA con OpenAI está encendida (talenti.ai.dora.enabled=true) pero falta "
						+ "la variable OPENAI_API_KEY. Ponla en tu .env o apaga DORA real con TALENTI_AI_DORA_ENABLED=false.");
			}
			return OpenAiTransport.http(access.baseUrl(), access.apiKey());
		}

		@Bean
		OpenAiLlm openAiLlm(DoraAiProperties ai, OpenAiTransport transport, JsonMapper mapper,
				DoraProperties properties) {
			return new OpenAiLlm(ai.models().dialog(), transport, mapper, properties.feedbackTimeout());
		}

		@Bean
		OpenAiSpeechToText openAiSpeechToText(DoraAiProperties ai, OpenAiTransport transport, JsonMapper mapper,
				DoraProperties properties) {
			return new OpenAiSpeechToText(ai.models().stt(), transport, mapper, properties.sttTimeout());
		}

		@Bean
		OpenAiTextToSpeech openAiTextToSpeech(DoraAiProperties ai, OpenAiTransport transport, JsonMapper mapper,
				DoraProperties properties) {
			return new OpenAiTextToSpeech(ai.models().tts(), ai.voice(), transport, mapper, properties.ttsTimeout());
		}

	}

	@Bean
	ConversationPort conversationPort(LlmPort llm, SpeechToTextPort stt, TextToSpeechPort tts,
			DoraProperties properties, @Qualifier("doraExecutor") ExecutorService executor) {
		return new TurnBasedConversation(llm, stt, tts,
				new TurnBasedConversation.Timeouts(properties.sttTimeout(), properties.bridgeTimeout(),
						properties.bridgeMax(), properties.ttsTimeout(), 2, 300),
				executor);
	}

}
