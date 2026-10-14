package com.talenti.matching.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 007-13: la explicación por plantilla se arma solo con el resultado estructurado, sin IA ni datos personales. */
class ExplanationWriterTests {

	private static final CompatibilityCalculator CALCULATOR = new CompatibilityCalculator(new MatchingParameters(50, 15,
			20, 15, 69, new BigDecimal("0.5"), new BigDecimal("0.20"), new BigDecimal("0.60"), 50, 80, 60, false));

	private final ExplanationWriter writer = new ExplanationWriter(templates(), 69);

	private static Map<String, String> templates() {
		Properties properties = new Properties();
		try (InputStream in = ExplanationWriterTests.class.getClassLoader()
				.getResourceAsStream("matching/explanation.es.properties");
				InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		Map<String, String> map = new HashMap<>();
		properties.forEach((key, value) -> map.put((String) key, (String) value));
		return map;
	}

	private static VacancyFacts.Requirement required(String slug, String name, int level) {
		return new VacancyFacts.Requirement(slug, name, RequirementKind.REQUIRED, level);
	}

	private static VacancyFacts.Requirement desirable(String slug, String name, int level) {
		return new VacancyFacts.Requirement(slug, name, RequirementKind.DESIRABLE, level);
	}

	private static VacancyFacts remote(VacancyFacts.Requirement... requirements) {
		return new VacancyFacts(Modality.REMOTE, null, 20, List.of(requirements));
	}

	private static ProfileFacts profile(Object... slugAndLevel) {
		Map<String, Integer> skills = new HashMap<>();
		for (int i = 0; i < slugAndLevel.length; i += 2) {
			skills.put((String) slugAndLevel[i], (Integer) slugAndLevel[i + 1]);
		}
		return new ProfileFacts(skills, Set.of(Modality.REMOTE), "Ciudad Secreta", 20);
	}

	private Explanation explain(ProfileFacts profile, VacancyFacts vacancy) {
		return writer.write(CALCULATOR.calculate(profile, vacancy, null));
	}

	@Test
	@DisplayName("007-13: viene como TEMPLATE con su versión, y la plantilla de recursos declara la versión 1")
	void t007_13_sourceAndVersion() {
		Explanation explanation = explain(profile("java", 2), remote(required("java", "Java", 2)));

		assertThat(explanation.source()).isEqualTo("TEMPLATE");
		assertThat(explanation.version()).isEqualTo("1");
		assertThat(writer.version()).isEqualTo("1");
	}

	@Test
	@DisplayName("007-13: cuenta los obligatorios cumplidos y nombra la brecha principal con los niveles en palabras")
	void t007_13_countsAndMainGap() {
		Explanation explanation = explain(profile("java", 2, "spring-boot", 1, "sql", 1), remote(
				required("java", "Java", 2), required("spring-boot", "Spring Boot", 1), required("sql", "SQL", 2),
				desirable("git", "Git", 1)));

		assertThat(explanation.text()).contains("Cumples 2 de 3 requisitos obligatorios.")
				.contains("La brecha principal es SQL: se pide nivel intermedio y tu perfil indica nivel básico.")
				.doesNotContain("máximo posible");
	}

	@Test
	@DisplayName("007-13: la brecha principal es primero un obligatorio ausente, luego uno parcial, luego un deseable")
	void t007_13_gapPriority() {
		VacancyFacts vacancy = remote(required("java", "Java", 3), required("sql", "SQL", 1),
				desirable("docker", "Docker", 1));

		Explanation missingRequired = explain(profile("java", 1), vacancy);
		Explanation partialRequired = explain(profile("java", 1, "sql", 1), vacancy);
		Explanation onlyDesirable = explain(profile("java", 3, "sql", 1), vacancy);

		assertThat(missingRequired.text()).contains("La brecha principal es SQL: es un requisito obligatorio que aún no figura");
		assertThat(partialRequired.text()).contains("La brecha principal es Java: se pide nivel avanzado y tu perfil indica nivel básico.");
		assertThat(onlyDesirable.text()).contains("Entre los requisitos deseables, aún no figura Docker en tu perfil.");
	}

	@Test
	@DisplayName("007-13: advierte del tope cuando falta un obligatorio, aunque el tope no haya bajado la cifra")
	void t007_13_capWarning() {
		Explanation explanation = explain(profile("java", 1), remote(required("java", "Java", 1),
				required("sql", "SQL", 1), required("react", "React", 1)));

		assertThat(explanation.text()).contains("el máximo posible para esta vacante es 69 %");
	}

	@Test
	@DisplayName("007-13: dice cuánta información usó y qué datos faltan en el perfil")
	void t007_13_missingInformation() {
		ProfileFacts profile = new ProfileFacts(Map.of("java", 2), Set.of(), null, null);

		Explanation explanation = explain(profile, remote(required("java", "Java", 2)));

		assertThat(explanation.text()).contains("Este resultado se basa en el 59 % de la información disponible.")
				.contains("completa en tu perfil: las modalidades que aceptas, tus horas por semana.");
	}

	@Test
	@DisplayName("007-13: si es insuficiente explica la razón en palabras sencillas")
	void t007_13_insufficientReasons() {
		Explanation noSkills = explain(new ProfileFacts(Map.of(), Set.of(), null, null),
				remote(required("java", "Java", 2)));
		Explanation lowInformation = explain(profile("git", 1), remote(desirable("git", "Git", 1)));
		Explanation noRequirements = explain(profile("git", 1), remote());

		assertThat(noSkills.text()).startsWith("Agrega al menos una habilidad");
		assertThat(lowInformation.text()).contains("no hay información suficiente");
		assertThat(noRequirements.text()).contains("no tiene requisitos registrados");
	}

	@Test
	@DisplayName("007-13: los mismos datos dan el mismo texto, y el texto no contiene datos del perfil como la ubicación")
	void t007_13_deterministicAndImpersonal() {
		ProfileFacts profile = profile("java", 1);
		VacancyFacts vacancy = remote(required("java", "Java", 2), required("sql", "SQL", 1));

		Explanation first = explain(profile, vacancy);
		Explanation second = explain(profile, vacancy);

		assertThat(second).isEqualTo(first);
		assertThat(first.text()).doesNotContain("Ciudad Secreta").doesNotContain("@");
	}

	@Test
	@DisplayName("007-13: si falta una plantilla falla con un error claro y no inventa texto")
	void t007_13_missingTemplateFails() {
		ExplanationWriter broken = new ExplanationWriter(Map.of("version", "1"), 69);

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> broken
				.write(CALCULATOR.calculate(profile("java", 2), remote(required("java", "Java", 2)), null)))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("plantilla");
	}

}
