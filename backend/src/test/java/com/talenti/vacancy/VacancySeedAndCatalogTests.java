package com.talenti.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.talenti.vacancy.domain.TextNormalizer;

/** Semilla de ejemplo encendida (004-03) y catálogo de habilidades (004-02, interfaz SkillCatalog). */
class VacancySeedAndCatalogTests extends VacancyIntegrationTest {

	@Autowired
	private SkillCatalog catalog;

	// ------------------------------------------------------------------------------------------ 004-03

	@Test
	@DisplayName("004-03: con la semilla encendida hay 8 empresas (4 destacadas) y 12 vacantes abiertas (4 destacadas)")
	void t004_03_companiesAndVacancies() {
		assertThat(count("SELECT count(*) FROM company")).isEqualTo(8);
		assertThat(count("SELECT count(*) FROM company WHERE featured")).isEqualTo(4);
		assertThat(count("SELECT count(*) FROM vacancy")).isEqualTo(12);
		assertThat(count("SELECT count(*) FROM vacancy WHERE status = 'OPEN'")).isEqualTo(12);
		assertThat(count("SELECT count(*) FROM vacancy WHERE featured")).isEqualTo(4);
		assertThat(jdbc.queryForList("SELECT name FROM company WHERE featured ORDER BY name", String.class))
				.containsExactly("Andes Tech", "Cóndor Datos", "Nexa Digital", "Tambo Cloud");
		assertThat(jdbc.queryForList("SELECT name FROM company ORDER BY name", String.class)).containsExactly(
				"Andes Tech", "Cóndor Datos", "Misti Software", "Nexa Digital", "Punto Código", "Quipu Systems",
				"Ruta Labs", "Tambo Cloud");
	}

	@Test
	@DisplayName("004-03: reparto de 4 remotas, 5 híbridas y 3 presenciales; Lima 5 y Arequipa 3")
	void t004_03_distribution() {
		Map<String, Integer> byModality = new HashMap<>();
		jdbc.query("SELECT modality, count(*) AS total FROM vacancy GROUP BY modality",
				rs -> {
					byModality.put(rs.getString("modality"), rs.getInt("total"));
				});
		assertThat(byModality).containsEntry("REMOTE", 4).containsEntry("HYBRID", 5).containsEntry("ONSITE", 3);
		assertThat(count("SELECT count(*) FROM vacancy WHERE location = 'Lima'")).isEqualTo(5);
		assertThat(count("SELECT count(*) FROM vacancy WHERE location = 'Arequipa'")).isEqualTo(3);
		assertThat(count("SELECT count(*) FROM vacancy WHERE modality = 'REMOTE' AND location IS NULL")).isEqualTo(4);
	}

	@Test
	@DisplayName("004-03: cada vacante tiene 3 requisitos obligatorios y 2 deseables")
	void t004_03_threeRequiredAndTwoDesirable() {
		List<String> wrong = jdbc.queryForList("""
				SELECT v.title FROM vacancy v
				WHERE (SELECT count(*) FROM vacancy_requirement r WHERE r.vacancy_id = v.id AND r.kind = 'REQUIRED') <> 3
				   OR (SELECT count(*) FROM vacancy_requirement r WHERE r.vacancy_id = v.id AND r.kind = 'DESIRABLE') <> 2""",
				String.class);

		assertThat(wrong).isEmpty();
	}

	@Test
	@DisplayName("004-03: 'Practicante Backend Java' tiene los requisitos del ejemplo de RN-01")
	void t004_03_backendJavaHasTheRn01Requirements() {
		List<String> requirements = new ArrayList<>(jdbc.queryForList("""
				SELECT r.kind || ' ' || s.slug || ' ' || r.min_level
				FROM vacancy_requirement r JOIN skill s ON s.id = r.skill_id
				JOIN vacancy v ON v.id = r.vacancy_id
				WHERE v.title = 'Practicante Backend Java'
				ORDER BY r.kind DESC, s.slug""", String.class));

		assertThat(requirements).containsExactly("REQUIRED java 2", "REQUIRED spring-boot 1", "REQUIRED sql 2",
				"DESIRABLE docker 1", "DESIRABLE git 1");
		assertThat(jdbc.queryForObject("SELECT modality || ' ' || hours_per_week FROM vacancy WHERE title = 'Practicante Backend Java'",
				String.class)).isEqualTo("REMOTE 20");
	}

	@Test
	@DisplayName("004-03: las fechas de publicación van de hoy a 14 días atrás, ninguna en el futuro")
	void t004_03_datesAreRelativeToTheLoad() {
		Integer outOfRange = count("""
				SELECT count(*) FROM vacancy
				WHERE id::text LIKE 'd0000000-%'
				  AND (published_at > now() OR published_at < now() - interval '15 days')""");

		assertThat(outOfRange).isZero();
	}

	@Test
	@DisplayName("004-03: la semilla es ficticia: sin sitios web ni logos, y nada de datos de personas")
	void t004_03_seedIsFictitious() {
		// No hay columnas de logo, web ni contacto en las empresas (spec 004, sección 5).
		List<String> columns = jdbc.queryForList(
				"SELECT column_name FROM information_schema.columns WHERE table_name = 'company'", String.class);
		assertThat(columns).containsExactlyInAnyOrder("id", "name", "city", "short_description", "featured",
				"created_at");
		assertThat(count("""
				SELECT count(*) FROM company c JOIN vacancy v ON v.company_id = c.id
				WHERE c.short_description ~* '(https?://|www\\.|@)' OR v.description ~* '(https?://|www\\.|@)'
				   OR v.summary ~* '(https?://|www\\.|@)'""")).isZero();
	}

	// ------------------------------------------------------------------------------------------ 004-02

	@Test
	@DisplayName("004-02: el catálogo tiene unas 30 habilidades con slug único y sinónimos")
	void t004_02_catalogHasAboutThirtySkills() {
		List<SkillInfo> skills = catalog.all();

		assertThat(skills).hasSizeBetween(28, 36);
		assertThat(skills).extracting(SkillInfo::slug).doesNotHaveDuplicates().allMatch(slug -> slug.matches("[a-z0-9-]+"));
		assertThat(skills).extracting(SkillInfo::name).doesNotHaveDuplicates();
		assertThat(skills.stream().filter(skill -> !skill.aliases().isEmpty()).count()).isGreaterThanOrEqualTo(20);
		assertThat(catalog.findBySlug("javascript")).get().satisfies(skill -> {
			assertThat(skill.name()).isEqualTo("JavaScript");
			assertThat(skill.aliases()).contains("JS");
		});
	}

	@Test
	@DisplayName("004-02: ningún sinónimo repite el nombre o sinónimo de otra habilidad (sin tildes ni mayúsculas)")
	void t004_02_noAliasIsShared() {
		Map<String, String> owner = new HashMap<>();
		List<String> conflicts = new ArrayList<>();
		for (SkillInfo skill : catalog.all()) {
			List<String> names = new ArrayList<>();
			names.add(skill.name());
			names.addAll(skill.aliases());
			for (String name : names) {
				String key = TextNormalizer.normalize(name);
				String previous = owner.putIfAbsent(key, skill.slug());
				if (previous != null) {
					conflicts.add(name + " (" + skill.slug() + ") repite a " + previous);
				}
			}
		}

		assertThat(conflicts).isEmpty();
	}

	@Test
	@DisplayName("004-02: la misma regla comprobada en la base de datos con unaccent")
	void t004_02_noAliasIsSharedInSql() {
		Integer duplicates = count("""
				WITH names AS (
				  SELECT slug, lower(unaccent(name)) AS term FROM skill
				  UNION ALL
				  SELECT slug, lower(unaccent(alias)) FROM skill, unnest(aliases) AS alias)
				SELECT count(*) FROM (SELECT term FROM names GROUP BY term HAVING count(*) > 1) d""");

		assertThat(duplicates).isZero();
	}

	@Test
	@DisplayName("004-02: SkillCatalog.resolve entiende nombres y sinónimos sin importar mayúsculas ni tildes")
	void t004_02_resolve() {
		assertThat(catalog.resolve("JS")).get().extracting(SkillInfo::slug).isEqualTo("javascript");
		assertThat(catalog.resolve(" javascript ")).get().extracting(SkillInfo::slug).isEqualTo("javascript");
		assertThat(catalog.resolve("NodeJS")).get().extracting(SkillInfo::slug).isEqualTo("node-js");
		assertThat(catalog.resolve("metodologias AGILES")).get().extracting(SkillInfo::slug).isEqualTo("scrum");
		assertThat(catalog.resolve("Postgres")).get().extracting(SkillInfo::slug).isEqualTo("postgresql");
		assertThat(catalog.resolve("COBOL")).isEmpty();
		assertThat(catalog.resolve("   ")).isEmpty();
		assertThat(catalog.resolve(null)).isEmpty();
		assertThat(catalog.findBySlug("no-existe")).isEmpty();
	}

}
