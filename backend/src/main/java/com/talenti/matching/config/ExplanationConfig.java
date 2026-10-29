package com.talenti.matching.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import com.talenti.matching.adapter.ExplainerTransport;
import com.talenti.matching.adapter.OpenAiCompatibilityExplainer;
import com.talenti.matching.adapter.SimulatedCompatibilityExplainer;
import com.talenti.matching.domain.ExplanationTemplate;
import com.talenti.matching.domain.ExplanationValidator;

import tools.jackson.databind.json.JsonMapper;

/**
 * Piezas de "DORA te explica" (spec 015). Qué adaptador se usa (015-16):
 * <ul>
 * <li>{@code enabled=false} (por defecto) y {@code simulated=false}: ninguno; siempre la plantilla, nada sale a la
 * red.</li>
 * <li>{@code enabled=false} y {@code simulated=true}: el simulado (solo pruebas y recorridos locales).</li>
 * <li>{@code enabled=true}: OpenAI; sin modelo o sin {@code OPENAI_API_KEY}, el arranque falla diciendo qué falta (y
 * nunca imprime la clave).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ ExplanationAiProperties.class, MatchingOpenAiAccess.class })
public class ExplanationConfig {

	static final String TEMPLATE = "matching/dora-explanation.es.properties";

	static final String FORBIDDEN = "matching/dora-explanation-forbidden.es.txt";

	@Bean
	ExplanationTemplate explanationTemplate() {
		return new ExplanationTemplate(properties(TEMPLATE));
	}

	@Bean
	ExplanationValidator explanationValidator() {
		return forbiddenList();
	}

	/** La lista versionada de frases prohibidas (015-08), también para las pruebas. */
	public static ExplanationValidator forbiddenList() {
		List<String> phrases = new ArrayList<>();
		String version = null;
		for (String line : lines(FORBIDDEN)) {
			String text = line.strip();
			if (text.isEmpty() || text.startsWith("#")) {
				continue;
			}
			if (text.startsWith("version=")) {
				version = text.substring("version=".length()).strip();
				continue;
			}
			phrases.add(text);
		}
		if (version == null || version.isBlank()) {
			throw new IllegalStateException("Falta la versión de la lista de frases prohibidas (" + FORBIDDEN + ")");
		}
		return new ExplanationValidator(phrases, version);
	}

	/** La plantilla de los recursos, también para las pruebas. */
	public static ExplanationTemplate templateFromResources() {
		return new ExplanationTemplate(properties(TEMPLATE));
	}

	/** Hilos virtuales para imponer el tiempo máximo a la llamada (015-10). */
	@Bean(name = "explanationExecutor", destroyMethod = "shutdownNow")
	ExecutorService explanationExecutor() {
		return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("explanation-", 0).factory());
	}

	@Bean
	@ConditionalOnExpression("!${talenti.ai.explanation.enabled:false} and ${talenti.ai.explanation.simulated:false}")
	SimulatedCompatibilityExplainer simulatedCompatibilityExplainer() {
		return new SimulatedCompatibilityExplainer();
	}

	@Bean
	@ConditionalOnProperty(name = "talenti.ai.explanation.enabled", havingValue = "true")
	OpenAiCompatibilityExplainer openAiCompatibilityExplainer(ExplanationAiProperties ai, MatchingOpenAiAccess access,
			JsonMapper mapper) {
		// Primero el modelo y luego la clave: el mensaje dice exactamente qué falta.
		if (ai.model() == null || ai.model().isBlank()) {
			throw new IllegalStateException("La explicación con IA está encendida (talenti.ai.explanation.enabled=true) "
					+ "pero falta talenti.ai.explanation.model (TALENTI_AI_EXPLANATION_MODEL; propuesto gpt-4.1-mini, "
					+ "a confirmar)");
		}
		return new OpenAiCompatibilityExplainer(ai.model(), ExplainerTransport.http(access.baseUrl(), access.apiKey()),
				mapper, ai.timeout(), ai.maxOutputTokens());
	}

	private static Map<String, String> properties(String resource) {
		Properties properties = new Properties();
		try (InputStream in = new ClassPathResource(resource).getInputStream();
				InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		catch (IOException e) {
			throw new UncheckedIOException("No se pudo leer " + resource, e);
		}
		Map<String, String> values = new HashMap<>();
		properties.forEach((key, value) -> values.put((String) key, (String) value));
		return values;
	}

	private static List<String> lines(String resource) {
		try (InputStream in = new ClassPathResource(resource).getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
		}
		catch (IOException e) {
			throw new UncheckedIOException("No se pudo leer " + resource, e);
		}
	}

}
