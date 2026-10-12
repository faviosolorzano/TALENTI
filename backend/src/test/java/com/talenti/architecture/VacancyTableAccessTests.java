package com.talenti.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 004-14 y principio 12: ningún módulo lee las tablas de otro. Revisa el texto de las consultas y de las
 * migraciones (Maven ejecuta las pruebas con backend/ como directorio de trabajo).
 */
class VacancyTableAccessTests {

	/** Tablas de los demás módulos (docs/architecture.md). vacancy no las nombra en ninguna consulta. */
	private static final Pattern FOREIGN_TABLES = Pattern.compile(
			"\\b(app_user|consent_record|audit_event|student_profile|student_skill|student_project|profile_embedding|"
					+ "vacancy_embedding|match_result|application_stage_event|practice_session|practice_turn)\\b");

	/** Tablas propias de vacancy. */
	private static final Pattern VACANCY_TABLES = Pattern
			.compile("\\b(company|vacancy|vacancy_requirement|skill)\\b");

	@Test
	@DisplayName("004-14: vacancy no lee tablas de otros módulos (ninguna consulta ni migración propia las nombra)")
	void t004_14_vacancySqlOnlyTouchesItsOwnTables() throws IOException {
		List<Path> vacancyFiles = new ArrayList<>();
		try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java", "com", "talenti", "vacancy"))) {
			sources.filter(path -> path.toString().endsWith(".java")).forEach(vacancyFiles::add);
		}
		vacancyFiles.add(Path.of("src", "main", "resources", "db", "migration", "V3__vacancy_and_skills.sql"));
		try (Stream<Path> demo = Files.walk(Path.of("src", "main", "resources", "db", "demo"))) {
			demo.filter(path -> path.toString().endsWith(".sql")).forEach(vacancyFiles::add);
		}

		assertThat(vacancyFiles).hasSizeGreaterThan(10);
		for (Path file : vacancyFiles) {
			String text = Files.readString(file);
			assertThat(FOREIGN_TABLES.matcher(text).find()).as("%s nombra una tabla de otro módulo", file).isFalse();
		}
	}

	@Test
	@DisplayName("004-14: ningún otro módulo lee las tablas de vacancy")
	void t004_14_otherModulesDoNotReadVacancyTables() throws IOException {
		try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java", "com", "talenti"))) {
			List<Path> others = sources.filter(path -> path.toString().endsWith(".java"))
					.filter(path -> !path.toString().replace('\\', '/').contains("/talenti/vacancy/")).toList();

			assertThat(others).isNotEmpty();
			for (Path file : others) {
				String text = Files.readString(file);
				// Solo se miran las consultas (texto entre comillas que empieza como SQL), no los comentarios.
				for (String sql : sqlStatements(text)) {
					assertThat(VACANCY_TABLES.matcher(sql).find()).as("%s consulta una tabla de vacancy", file).isFalse();
				}
			}
		}
	}

	private static List<String> sqlStatements(String source) {
		Matcher matcher = Pattern
				.compile("\"\"\"(.*?)\"\"\"|\"((?:SELECT|INSERT|UPDATE|DELETE|WITH)\\b[^\"]*)\"", Pattern.DOTALL)
				.matcher(source);
		List<String> statements = new ArrayList<>();
		while (matcher.find()) {
			String text = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
			if (text.stripLeading().toUpperCase(Locale.ROOT).matches("(?s)^(SELECT|INSERT|UPDATE|DELETE|WITH).*")) {
				statements.add(text);
			}
		}
		return statements;
	}

}
