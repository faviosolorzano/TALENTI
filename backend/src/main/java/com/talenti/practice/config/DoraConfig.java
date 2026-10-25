package com.talenti.practice.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.talenti.practice.domain.DoraTexts;
import com.talenti.practice.domain.QuestionBank;
import com.talenti.practice.domain.QuestionPlanner;
import com.talenti.practice.domain.Rubric;
import com.talenti.practice.service.InterviewNotice;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Piezas de DORA (spec 012): los recursos versionados (banco base, textos fijos, rúbrica y aviso de la entrevista) y
 * los hilos VIRTUALES de Java 21 en los que corren los flujos SSE, la voz, la IA y la medición de consumo.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ DoraProperties.class, DoraAiProperties.class, OpenAiAccess.class })
@EnableScheduling
class DoraConfig {

	/** Un hilo virtual por tarea: miles de esperas de red sin agotar hilos del sistema. */
	@Bean(name = "doraExecutor", destroyMethod = "shutdownNow")
	ExecutorService doraExecutor() {
		return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("dora-", 0).factory());
	}

	@Bean
	QuestionBank questionBank(ResourceLoader resources, JsonMapper mapper) throws IOException {
		JsonNode root = read(resources, mapper, "classpath:practice/question-bank-v1.json");
		Map<String, List<String>> bySkill = new LinkedHashMap<>();
		root.path("skills").properties().forEach(entry -> bySkill.put(entry.getKey(), strings(entry.getValue())));
		return new QuestionBank(root.path("version").asString(), bySkill, strings(root.path("anySkill")),
				strings(root.path("anyProject")), strings(root.path("general")));
	}

	@Bean
	QuestionPlanner questionPlanner(QuestionBank bank) {
		return new QuestionPlanner(bank);
	}

	@Bean
	DoraTexts doraTexts(ResourceLoader resources, JsonMapper mapper) throws IOException {
		JsonNode root = read(resources, mapper, "classpath:practice/dora-texts-v1.json");
		return new DoraTexts(root.path("version").asString(), root.path("greeting").asString(),
				root.path("greetingWithName").asString(), root.path("practiceNotice").asString(),
				root.path("interviewNotice").asString(), root.path("closingPractice").asString(),
				root.path("closingInterview").asString(), strings(root.path("templateBridges")),
				root.path("templateBridgeLast").asString());
	}

	@Bean
	Rubric rubric(ResourceLoader resources, JsonMapper mapper, DoraProperties properties) throws IOException {
		JsonNode root = read(resources, mapper, "classpath:practice/rubric-v1.json");
		List<Rubric.Criterion> criteria = new ArrayList<>();
		for (JsonNode node : root.path("criteria")) {
			criteria.add(new Rubric.Criterion(node.path("code").asString(), node.path("name").asString(),
					node.path("description").asString()));
		}
		return new Rubric(root.path("version").asString(), criteria, properties.rubricWeights());
	}

	@Bean
	InterviewNotice interviewNotice(ResourceLoader resources, DoraProperties properties) throws IOException {
		String version = properties.interview().noticeVersion();
		try (InputStream input = resources.getResource("classpath:practice/interview-notice-" + version + ".md")
				.getInputStream()) {
			return new InterviewNotice(version, new String(input.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	private static JsonNode read(ResourceLoader resources, JsonMapper mapper, String location) throws IOException {
		try (InputStream input = resources.getResource(location).getInputStream()) {
			return mapper.readTree(new String(input.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	private static List<String> strings(JsonNode array) {
		List<String> values = new ArrayList<>();
		for (JsonNode item : array) {
			values.add(item.asString());
		}
		return values;
	}

}
