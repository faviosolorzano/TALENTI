package com.talenti.cv.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.talenti.cv.EnhancedCvExtractionPort;
import com.talenti.cv.adapter.OpenAiCvExtractionAdapter;
import com.talenti.cv.adapter.RulesCvExtractionAdapter;
import com.talenti.cv.domain.CareerDictionary;
import com.talenti.cv.domain.PersonalDataReducer;
import com.talenti.cv.service.OriginRateLimiter;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.json.JsonMapper;

/**
 * Piezas del lector de CV (spec 009). Hay DOS adaptadores posibles a la vez: el de reglas (siempre) y el de IA, que
 * solo existe si {@code talenti.ai.cv.enabled=true} y {@code AI_PROVIDER=openai} (apagado hasta S10).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ CvProperties.class, AiCvProperties.class })
@EnableScheduling
class CvConfig {

	@Bean
	PersonalDataReducer personalDataReducer() {
		return new PersonalDataReducer();
	}

	@Bean
	CareerDictionary careerDictionary(CvProperties properties, ResourceLoader resources) throws IOException {
		try (InputStream input = resources.getResource(properties.careersResource()).getInputStream()) {
			return new CareerDictionary(List.of(new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")));
		}
	}

	@Bean
	RulesCvExtractionAdapter rulesCvExtractionAdapter(CareerDictionary careers) {
		return new RulesCvExtractionAdapter(careers);
	}

	/** Solo si se enciende la lectura mejorada con OpenAI. Sin clave o sin modelo, el arranque falla (009-11). */
	@Bean
	@ConditionalOnExpression("${talenti.ai.cv.enabled:false} and '${talenti.ai.provider:simulated}' == 'openai'")
	OpenAiCvExtractionAdapter openAiCvExtractionAdapter(AiCvProperties ai, JsonMapper mapper) {
		return new OpenAiCvExtractionAdapter(ai.cv().model(), ai.openai().apiKey(), ai.openai().baseUrl(),
				ai.cv().maxOutputTokens(), ai.cv().timeout(), mapper, OpenAiCvExtractionAdapter.httpTransport());
	}

	/** Trabajos de lectura: pocos a la vez y una cola corta; si se llena, la lectura falla con {@code BUSY}. */
	@Bean(destroyMethod = "shutdownNow")
	ThreadPoolExecutor cvJobs() {
		ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(50),
				runnable -> {
					Thread thread = new Thread(runnable, "cv-read");
					thread.setDaemon(true);
					return thread;
				});
		executor.allowCoreThreadTimeOut(true);
		return executor;
	}

	@Bean
	OriginRateLimiter cvOriginRateLimiter(CvProperties properties, Clock clock) {
		return new OriginRateLimiter(properties.originLimitPerMinute(), Duration.ofMinutes(1), clock);
	}

	/** Encender la lectura mejorada sin su implementación no se puede: el arranque falla con un mensaje claro. */
	@Bean
	CvAiStartupCheck cvAiStartupCheck(AiCvProperties ai, ObjectProvider<EnhancedCvExtractionPort> adapter) {
		return new CvAiStartupCheck(ai, adapter);
	}

	/** Comprueba la configuración de IA al arrancar. */
	static final class CvAiStartupCheck {

		private final AiCvProperties ai;

		private final ObjectProvider<EnhancedCvExtractionPort> adapter;

		CvAiStartupCheck(AiCvProperties ai, ObjectProvider<EnhancedCvExtractionPort> adapter) {
			this.ai = ai;
			this.adapter = adapter;
		}

		@PostConstruct
		void check() {
			if (ai.cv().enabled() && adapter.getIfAvailable() == null) {
				throw new IllegalStateException("talenti.ai.cv.enabled=true pero no hay un adaptador de IA para la "
						+ "lectura del CV (AI_PROVIDER=" + ai.provider() + "). La lectura mejorada llega con S10.");
			}
		}

	}

}
