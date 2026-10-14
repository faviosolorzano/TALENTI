package com.talenti.matching.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import com.talenti.matching.domain.CompatibilityCalculator;
import com.talenti.matching.domain.ExplanationWriter;
import com.talenti.matching.domain.MatchingParameters;

/** Piezas del módulo matching que salen de la configuración. Un parámetro incoherente impide el arranque. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MatchingProperties.class)
class MatchingConfig {

	static final String EXPLANATION_TEMPLATES = "matching/explanation.es.properties";

	@Bean
	MatchingParameters matchingParameters(MatchingProperties properties) {
		MatchingProperties.Weights weights = properties.weights();
		return new MatchingParameters(weights.required(), weights.desirable(), weights.conditions(),
				weights.similarity(), properties.cap(), properties.partialCredit(), properties.cosine().low(),
				properties.cosine().high(), properties.minEvaluableWeight(), properties.verdict().high(),
				properties.verdict().medium(), properties.similarity().enabled());
	}

	@Bean
	CompatibilityCalculator compatibilityCalculator(MatchingParameters parameters) {
		return new CompatibilityCalculator(parameters);
	}

	@Bean
	ExplanationWriter explanationWriter(MatchingParameters parameters) {
		return new ExplanationWriter(loadTemplates(), parameters.cap());
	}

	private static Map<String, String> loadTemplates() {
		Properties properties = new Properties();
		try (InputStream in = new ClassPathResource(EXPLANATION_TEMPLATES).getInputStream();
				InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		catch (IOException e) {
			throw new UncheckedIOException("No se pudieron leer las plantillas de la explicación", e);
		}
		Map<String, String> templates = new HashMap<>();
		properties.forEach((key, value) -> templates.put((String) key, (String) value));
		return templates;
	}

}
