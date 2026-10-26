package com.talenti.github.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 013-08 y 013-10: porcentajes por bytes, detección por reglas y el mapa versionado (código puro). */
class TechnologyDetectorTests {

	private static final Map<String, TechnologyDetector.CatalogSkill> CATALOG = Map.of(
			"java", new TechnologyDetector.CatalogSkill("java", "Java"),
			"docker", new TechnologyDetector.CatalogSkill("docker", "Docker"),
			"postgresql", new TechnologyDetector.CatalogSkill("postgresql", "PostgreSQL"),
			"react", new TechnologyDetector.CatalogSkill("react", "React"));

	private static TechnologyMap realMap() throws IOException {
		try (InputStream input = TechnologyDetectorTests.class.getResourceAsStream("/github/technology-map.properties")) {
			return TechnologyMap.parse(List.of(new String(input.readAllBytes(), StandardCharsets.UTF_8).split("\\R")));
		}
	}

	private static TechnologyDetector detector(TechnologyMap map) {
		return new TechnologyDetector(10, name -> map.slugFor(name).or(() -> Optional.of(name.toLowerCase()))
				.map(CATALOG::get));
	}

	private static Map<String, Long> bytes(Object... pairs) {
		Map<String, Long> map = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put((String) pairs[i], ((Number) pairs[i + 1]).longValue());
		}
		return map;
	}

	// ------------------------------------------------------------------------------------------ 013-08

	@Test
	@DisplayName("013-08: el porcentaje sale de los bytes, redondeado a 1 decimal y de mayor a menor")
	void t013_08_percentByBytes() {
		List<LanguageShare> shares = LanguageShare.of(bytes("CSS", 1, "Java", 2, "HTML", 0, "", 5));

		assertThat(shares).extracting(LanguageShare::name).containsExactly("Java", "CSS");
		assertThat(shares).extracting(LanguageShare::percent).containsExactly(new BigDecimal("66.7"), new BigDecimal("33.3"));
		assertThat(LanguageShare.of(bytes("Java", 3, "Kotlin", 1, "Go", 1, "Rust", 1))).extracting(LanguageShare::percent)
				.containsExactly(new BigDecimal("50.0"), new BigDecimal("16.7"), new BigDecimal("16.7"), new BigDecimal("16.7"));
		// Un repositorio sin código (vacío o solo documentos) queda sin lenguajes.
		assertThat(LanguageShare.of(Map.of())).isEmpty();
	}

	// ------------------------------------------------------------------------------------------ 013-10

	@Test
	@DisplayName("013-10: lenguajes con al menos 10 % y todos los temas; el mapa resuelve Dockerfile y PLpgSQL; lo demás queda unmatched")
	void t013_10_detection() throws IOException {
		TechnologyDetector.SelectedRepo api = new TechnologyDetector.SelectedRepo("api",
				LanguageShare.of(bytes("Java", 800, "Dockerfile", 100, "PLpgSQL", 95, "Shell", 5)), List.of("ReactJS", "rara"));
		TechnologyDetector.SelectedRepo web = new TechnologyDetector.SelectedRepo("web",
				LanguageShare.of(bytes("Java", 100)), List.of());

		TechnologyDetector.Detection detection = detector(realMap()).detect(List.of(api, web));

		// PLpgSQL tiene 9,5 %: no llega al 10 %. Shell, 0,5 %.
		assertThat(detection.suggestions()).extracting(TechnologyDetector.Suggestion::slug)
				.containsExactly("docker", "java", "react");
		TechnologyDetector.Suggestion java = detection.suggestions().get(1);
		assertThat(java.evidence()).extracting(TechnologyDetector.Evidence::repo).containsExactly("api", "web");
		assertThat(java.evidence().get(0).source()).isEqualTo(TechnologyDetector.Source.LANGUAGE);
		assertThat(java.evidence().get(0).share()).isEqualByComparingTo("80.0");
		assertThat(detection.suggestions().get(2).evidence().get(0).source()).isEqualTo(TechnologyDetector.Source.TOPIC);
		assertThat(detection.unmatched()).extracting(TechnologyDetector.Unmatched::name).containsExactly("rara");
	}

	@Test
	@DisplayName("013-10 y principio 11: el mapa de nombres de GitHub está versionado; una línea mal formada se rechaza")
	void t013_10_mapIsVersioned() throws IOException {
		TechnologyMap map = realMap();

		assertThat(map.version()).isEqualTo("2026.10-1");
		assertThat(map.slugFor("Dockerfile")).contains("docker");
		assertThat(map.slugFor("plpgsql")).contains("postgresql");
		assertThat(map.slugFor("Java")).isEmpty();
		assertThatThrownBy(() -> TechnologyMap.parse(List.of("sin-igual"))).isInstanceOf(IllegalArgumentException.class);
	}

}
