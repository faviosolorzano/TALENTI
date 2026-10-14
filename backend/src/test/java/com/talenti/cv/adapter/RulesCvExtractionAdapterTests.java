package com.talenti.cv.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.talenti.cv.RawSuggestion;
import com.talenti.cv.domain.CareerDictionary;
import com.talenti.cv.domain.PersonalDataReducer;
import com.talenti.support.SampleCv;
import com.talenti.vacancy.SkillInfo;

/** 009-12: el adaptador por reglas, con CV inventados. Es una búsqueda, no una comprensión. */
class RulesCvExtractionAdapterTests {

	private final List<SkillInfo> catalog = SampleCv.catalog();

	private final RulesCvExtractionAdapter adapter = new RulesCvExtractionAdapter(careers());

	private static CareerDictionary careers() {
		try {
			return new CareerDictionary(List.of(new String(new ClassPathResource("cv/careers.txt").getInputStream()
					.readAllBytes(), StandardCharsets.UTF_8).split("\\R")));
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private List<RawSuggestion> extract(String text) {
		return adapter.extract(new PersonalDataReducer().reduce(text), catalog);
	}

	private static List<String> slugs(List<RawSuggestion> suggestions) {
		return suggestions.stream().filter(s -> s.kind().equals("SKILL")).map(RawSuggestion::slug).toList();
	}

	private static RawSuggestion academic(List<RawSuggestion> suggestions, String field) {
		return suggestions.stream().filter(s -> s.kind().equals("ACADEMIC") && field.equals(s.field())).findFirst()
				.orElse(null);
	}

	@Test
	@DisplayName("009-12: detecta habilidades por nombre y por sinónimo, sin nivel y con la línea como evidencia")
	void t009_12_skillsByNameAndAlias() {
		List<RawSuggestion> result = extract("Habilidades\nJava, Spring Boot y Postgres\nTrabajo con JS y NodeJS\n");

		assertThat(slugs(result)).containsExactly("java", "spring-boot", "postgresql", "javascript", "node-js");
		assertThat(result).filteredOn(s -> s.kind().equals("SKILL")).allSatisfy(s -> {
			assertThat(s.level()).as("el CV no dice el nivel").isNull();
			assertThat(s.evidence()).isNotBlank();
		});
		RawSuggestion postgres = result.stream().filter(s -> "postgresql".equals(s.slug())).findFirst().orElseThrow();
		assertThat(postgres.evidence()).isEqualTo("Java, Spring Boot y Postgres");
	}

	@Test
	@DisplayName("009-12: la palabra debe ser completa y no distingue mayúsculas ni tildes: Java no sale de JavaScript, Git no sale de Digital")
	void t009_12_wholeWordsOnly() {
		List<RawSuggestion> result = extract("""
				Habilidades
				Marketing Digital y diseño gráfico
				Experiencia con JavaScript y TypeScript
				Estudié Javanés y Nodemailer
				pruebas de software y REST
				""");

		assertThat(slugs(result)).containsExactly("javascript", "testing", "rest-apis");
		assertThat(slugs(extract("trabajo con JAVA y con c# todos los días"))).containsExactly("java", "csharp");
		assertThat(slugs(extract("Programación en ASP.NET"))).containsExactly("dotnet");
	}

	@Test
	@DisplayName("009-12: cada habilidad se sugiere una sola vez aunque aparezca varias veces")
	void t009_12_noDuplicates() {
		List<RawSuggestion> result = extract("Java\nJava y SQL\nsql\nJAVA");

		assertThat(slugs(result)).containsExactly("java", "sql");
	}

	@Test
	@DisplayName("009-12: encuentra la carrera por el diccionario y prefiere la frase más larga")
	void t009_12_career() {
		RawSuggestion career = academic(extract("Educación\nInstituto Tecsup - Diseño y Desarrollo de Software\n"), "CAREER");

		assertThat(career.value()).isEqualTo("Diseño y Desarrollo de Software");
		assertThat(career.evidence()).contains("Tecsup");
		assertThat(academic(extract("Estudié Computación e Informática"), "CAREER").value())
				.isEqualTo("Computación e Informática");
		assertThat(academic(extract("Sin estudios aquí\nJava"), "CAREER")).isNull();
	}

	@Test
	@DisplayName("009-12: encuentra el instituto o la universidad por su palabra clave")
	void t009_12_institute() {
		assertThat(academic(extract("Educación\nInstituto Tecsup - Desarrollo de Software (2023 - 2026)"),
				"INSTITUTE").value()).isEqualTo("Instituto Tecsup");
		assertThat(academic(extract("Estudios\nUniversidad Nacional de Ingeniería, Lima"), "INSTITUTE").value())
				.isEqualTo("Universidad Nacional de Ingeniería");
		assertThat(academic(extract("Educación\nSENATI | Computación e Informática"), "INSTITUTE").value())
				.isEqualTo("SENATI");
		assertThat(academic(extract("Solo habilidades\nJava"), "INSTITUTE")).isNull();
	}

	@Test
	@DisplayName("009-12: encuentra el ciclo en distintos formatos y solo de 1 a 10")
	void t009_12_cycle() {
		for (String line : List.of("5.º ciclo", "5to ciclo", "5° ciclo", "5 ciclo", "ciclo 5", "Ciclo: 5",
				"Cursando el quinto ciclo", "ciclo quinto")) {
			assertThat(academic(extract("Educación\nTecsup - " + line), "CYCLE"))
					.as(line).isNotNull().extracting(RawSuggestion::value).isEqualTo("5");
		}
		assertThat(academic(extract("Educación\n10mo ciclo"), "CYCLE").value()).isEqualTo("10");
		assertThat(academic(extract("Educación\n12.º ciclo"), "CYCLE")).as("fuera de 1 a 10").isNull();
		assertThat(academic(extract("Educación\nciclo 0"), "CYCLE")).isNull();
		assertThat(academic(extract("Educación\nTecsup 2022 ciclo"), "CYCLE")).isNull();
	}

	@Test
	@DisplayName("009-12: los proyectos salen de los bloques bajo el encabezado Proyectos, con descripción y tecnologías del catálogo")
	void t009_12_projects() {
		List<RawSuggestion> result = extract("""
				Habilidades
				Python
				Proyectos
				Tienda Web
				Aplicación de comercio electrónico hecha con Java y Spring Boot.
				- Base de datos PostgreSQL con Docker
				Sistema de Notas: API REST para registrar notas usando Node y MongoDB.
				Experiencia
				Practicante de soporte
				""");

		List<RawSuggestion> projects = result.stream().filter(s -> s.kind().equals("PROJECT")).toList();
		assertThat(projects).hasSize(2);
		assertThat(projects.get(0).name()).isEqualTo("Tienda Web");
		assertThat(projects.get(0).description()).contains("comercio electrónico").contains("PostgreSQL con Docker");
		assertThat(projects.get(0).technologies()).containsExactly("Java", "Spring Boot", "PostgreSQL", "Docker");
		assertThat(projects.get(0).evidence()).isEqualTo("Tienda Web");
		assertThat(projects.get(1).name()).isEqualTo("Sistema de Notas");
		assertThat(projects.get(1).technologies()).contains("REST APIs", "Node.js", "MongoDB");
	}

	@Test
	@DisplayName("009-12: sin sección de proyectos, sin estudios o en otro idioma simplemente hay menos sugerencias")
	void t009_12_fewerSuggestions() {
		assertThat(extract("Habilidades\nJava y SQL")).extracting(RawSuggestion::kind).containsOnly("SKILL");
		assertThat(extract("Texto sin datos conocidos de ninguna clase")).isEmpty();
		assertThat(slugs(extract("Skills\nJava, Docker\nProjects\nOnline Store\nAn e-commerce built with React."))).contains("java", "docker", "react");
		assertThat(extract("Projects\nOnline Store\nAn e-commerce built with React.")).filteredOn(s -> s.kind().equals("PROJECT")).hasSize(1);
	}

	@Test
	@DisplayName("009-12: es determinista: el mismo texto da las mismas sugerencias")
	void t009_12_deterministic() {
		String text = String.join("\n", SampleCv.lines("MARCADETERMINISTA"));

		List<RawSuggestion> first = extract(text);
		List<RawSuggestion> second = extract(text);

		assertThat(first).isNotEmpty().usingRecursiveComparison().isEqualTo(second);
	}

	@Test
	@DisplayName("009-12: un CV completo inventado da carrera, instituto, ciclo, habilidades y proyectos, sin datos de contacto")
	void t009_12_fullSampleCv() {
		List<RawSuggestion> result = extract(String.join("\n", SampleCv.lines("MARCAX")));

		assertThat(academic(result, "CAREER").value()).isEqualTo("Diseño y Desarrollo de Software");
		assertThat(academic(result, "INSTITUTE").value()).isEqualTo("Instituto Tecsup");
		assertThat(academic(result, "CYCLE").value()).isEqualTo("5");
		assertThat(slugs(result)).contains("java", "spring-boot", "sql", "postgresql", "docker", "javascript", "react",
				"node-js", "mongodb", "rest-apis").doesNotContain("testing");
		// "GitHub" es sinónimo de Git y el enlace al repositorio se conserva; "Digital" no es Git.
		RawSuggestion git = result.stream().filter(s -> "git".equals(s.slug())).findFirst().orElseThrow();
		assertThat(git.evidence()).startsWith("https://github.com/");
		assertThat(result.stream().filter(s -> s.kind().equals("PROJECT"))).hasSize(2);
		String everything = result.stream().map(s -> s.evidence() + s.value() + s.name() + s.description())
				.reduce("", String::concat);
		assertThat(everything).doesNotContain(SampleCv.EMAIL).doesNotContain(SampleCv.DNI)
				.doesNotContain("987 654").doesNotContain("Quispe").doesNotContain("Los Pinos");
	}

	@Test
	@DisplayName("009-11: cada análisis guarda quién lo produjo: adaptador, versión y versión de las reglas")
	void t009_11_adapterInfo() {
		assertThat(adapter.info().name()).isEqualTo("rules");
		assertThat(adapter.info().version()).isNotBlank();
		assertThat(adapter.info().ruleSetVersion()).isNotBlank();
		assertThat(adapter.info().model()).isNull();
	}

}
