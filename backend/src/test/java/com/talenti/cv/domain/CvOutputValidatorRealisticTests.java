package com.talenti.cv.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.cv.RawSuggestion;
import com.talenti.cv.SuggestionKind;
import com.talenti.support.SampleCv;
import com.talenti.vacancy.SkillInfo;

/**
 * 009-13 (T3): salidas REALISTAS de una IA, que antes se descartaban sin motivo (campos en blanco, citas
 * parafraseadas, comillas, mayúsculas distintas) y ahora pasan, y evidencias inventadas de verdad, que se siguen
 * descartando. El CV es inventado.
 */
class CvOutputValidatorRealisticTests {

	private static final String TEXT = """
			Estudiante de Desarrollo de Software
			Instituto TECSUP, ciclo 5
			Habilidades: Java, Spring Boot, SQL, PostgreSQL, Git, Docker, React
			Proyecto: Sistema de inventario con Java y Spring Boot, base de datos PostgreSQL
			Proyecto: Portal web con React y TypeScript""";

	private final List<SkillInfo> catalog = SampleCv.catalog();

	private final CvOutputValidator validator = new CvOutputValidator(
			slug -> catalog.stream().filter(s -> s.slug().equals(slug)).findFirst(),
			text -> catalog.stream().filter(s -> Fold.of(s.name()).equals(Fold.of(text))
					|| s.aliases().stream().anyMatch(a -> Fold.of(a).equals(Fold.of(text)))).findFirst(),
			10);

	private final Map<String, Integer> discards = new LinkedHashMap<>();

	private List<SuggestionDraft> validate(RawSuggestion... items) {
		return validator.validate(List.of(items), TEXT, discards);
	}

	/** Lo que hace un modelo con un esquema estricto: llena lo que no corresponde con "" y [] en vez de null. */
	private static RawSuggestion strictSkill(String slug, String name, Integer level, String evidence) {
		return new RawSuggestion("SKILL", "", slug, name, "", level, "", List.of(), evidence);
	}

	@Test
	@DisplayName("009-13: campos en blanco (cadenas vacías, listas vacías, nivel 0) no descartan una habilidad real")
	void t009_13_blankFieldsAreAbsent() {
		List<SuggestionDraft> result = validate(strictSkill("java", "Java", 0, "Java, Spring Boot, SQL"),
				strictSkill("postgresql", "PostgreSQL", null, "base de datos PostgreSQL"));

		assertThat(result).hasSize(2);
		assertThat(result.get(0).payload()).containsEntry("slug", "java").containsEntry("level", null);
		assertThat(discards).isEmpty();
	}

	@Test
	@DisplayName("009-13: una cita parafraseada, recortada, con comillas o con otra puntuación se acepta si sus palabras están en el texto")
	void t009_13_tolerantEvidence() {
		List<SuggestionDraft> result = validate(
				strictSkill("react", "React", null, "\"Portal web con React y TypeScript\""),
				strictSkill("docker", "Docker", null, "Habilidades - Java; Spring Boot; SQL; PostgreSQL; Git; Docker"),
				strictSkill("git", "Git", null, "habilidades: git y docker"),
				new RawSuggestion("PROJECT", "", "", "Sistema de inventario", "", 0,
						"Sistema de inventario hecho con Java y Spring Boot", List.of("Java", "Spring Boot", "PostgreSQL"),
						"Proyecto: Sistema de inventario con Java y Spring Boot"));

		assertThat(result).extracting(SuggestionDraft::kind).containsExactly(SuggestionKind.SKILL, SuggestionKind.SKILL,
				SuggestionKind.SKILL, SuggestionKind.PROJECT);
		assertThat(discards).isEmpty();
	}

	@Test
	@DisplayName("009-13: mayúsculas y nombre distinto del catálogo ('postgres', 'JAVA'), y slug nulo para algo fuera del catálogo")
	void t009_13_caseAliasAndNullSlug() {
		List<SuggestionDraft> result = validate(strictSkill(null, "JAVA", 2, "Habilidades: Java"),
				strictSkill(null, "Postgres", null, "base de datos PostgreSQL"),
				strictSkill("", "TypeScript", null, "Portal web con React y TypeScript"));

		assertThat(result).hasSize(3);
		assertThat(result.get(0).payload()).containsEntry("slug", "java").containsEntry("name", "Java");
		assertThat(result.get(1).payload()).containsEntry("slug", "postgresql");
		assertThat(result.get(2).payload()).containsEntry("slug", null).containsEntry("name", "TypeScript");
	}

	@Test
	@DisplayName("009-13: estudios con campos en blanco y ciclo como número pasan")
	void t009_13_academicWithBlankFields() {
		List<SuggestionDraft> result = validate(
				new RawSuggestion("ACADEMIC", "CAREER", "", "", "Desarrollo de Software", 0, "", List.of(),
						"Estudiante de Desarrollo de Software"),
				new RawSuggestion("ACADEMIC", "INSTITUTE", null, null, "TECSUP", null, null, null, "Instituto TECSUP, ciclo 5"),
				new RawSuggestion("ACADEMIC", "CYCLE", null, null, "5", null, null, null, "Instituto TECSUP, ciclo 5"));

		assertThat(result).extracting(SuggestionDraft::field).containsExactly("CAREER", "INSTITUTE", "CYCLE");
		assertThat(result.get(2).payload()).containsEntry("value", 5);
	}

	@Test
	@DisplayName("009-13 (T3): una evidencia inventada de verdad se descarta, aunque la habilidad exista en el catálogo")
	void t009_13_inventedEvidenceIsDiscarded() {
		List<SuggestionDraft> result = validate(
				strictSkill("java", "Java", 3, "Lideró un equipo de diez personas en una empresa bancaria"),
				strictSkill("mongodb", "MongoDB", null, "Experiencia con MongoDB y Redis en producción"),
				new RawSuggestion("PROJECT", null, null, "App móvil de pagos", null, null, "Una app de pagos",
						List.of("Kotlin"), "Desarrolló una aplicación móvil de pagos para un banco"));

		assertThat(result).isEmpty();
		assertThat(discards).containsEntry(CvOutputValidator.R_EVIDENCE, 3);
	}

	@Test
	@DisplayName("009-13 (T3): una habilidad que el CV no nombra se descarta aunque la cita sea real")
	void t009_13_skillNotNamedIsDiscarded() {
		List<SuggestionDraft> result = validate(strictSkill("mongodb", "MongoDB", null, "Habilidades: Java, Spring Boot, SQL, PostgreSQL"));

		assertThat(result).isEmpty();
		assertThat(discards).containsEntry(CvOutputValidator.R_SUBJECT, 1);
	}

	@Test
	@DisplayName("009-13: los descartes se cuentan por motivo, sin contenido")
	void t009_13_discardsAreCountedByReason() {
		validate(new RawSuggestion("SKILL", null, "java", "Java", "algo", null, null, null, "Habilidades: Java"),
				new RawSuggestion("ACADEMIC", "CYCLE", null, null, "99", null, null, null, "Instituto TECSUP, ciclo 5"),
				strictSkill("java", "Java", null, "Habilidades: Java"), strictSkill("java", "Java", null, "Habilidades: Java"));

		assertThat(discards).containsEntry(CvOutputValidator.R_SHAPE, 1).containsEntry(CvOutputValidator.R_VALUE, 1)
				.containsEntry(CvOutputValidator.R_DUPLICATE, 1);
	}

}
