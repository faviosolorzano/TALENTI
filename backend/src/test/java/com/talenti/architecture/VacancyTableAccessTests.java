package com.talenti.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 004-14 y principio 12: ningún módulo lee las tablas de otro. Revisa el texto de las consultas y de las
 * migraciones (Maven ejecuta las pruebas con backend/ como directorio de trabajo).
 *
 * <p>Las listas de tablas no se escriben a mano: salen de los {@code CREATE TABLE} de las migraciones reales. Las
 * tablas de vacancy son las que crea su(s) migración(es) (el nombre del archivo contiene "vacancy"); todas las
 * demás son ajenas, así que una tabla nueva de otro módulo queda cubierta sola.
 */
class VacancyTableAccessTests {

	private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

	private static final Pattern CREATE_TABLE = Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)",
			Pattern.CASE_INSENSITIVE);

	/** Tablas que crean las migraciones cuyo nombre cumple {@code fileFilter}. */
	private static Set<String> tablesCreatedBy(java.util.function.Predicate<String> fileFilter) throws IOException {
		Set<String> tables = new LinkedHashSet<>();
		try (Stream<Path> files = Files.list(MIGRATIONS)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".sql"))
					.filter(path -> fileFilter.test(path.getFileName().toString())).toList()) {
				Matcher matcher = CREATE_TABLE.matcher(Files.readString(file));
				while (matcher.find()) {
					tables.add(matcher.group(1).toLowerCase(Locale.ROOT));
				}
			}
		}
		return tables;
	}

	private static Pattern anyOf(Set<String> tables) {
		return Pattern.compile("\\b(" + String.join("|", tables) + ")\\b", Pattern.CASE_INSENSITIVE);
	}

	@Test
	@DisplayName("004-14: las listas de tablas salen de las migraciones: vacancy tiene las suyas y hay ajenas")
	void t004_14_tableListsComeFromTheMigrations() throws IOException {
		Set<String> own = tablesCreatedBy(name -> name.contains("vacancy"));
		Set<String> foreign = tablesCreatedBy(name -> !name.contains("vacancy"));

		assertThat(own).containsExactlyInAnyOrder("company", "skill", "vacancy", "vacancy_requirement");
		assertThat(foreign).isNotEmpty().contains("app_user").doesNotContainAnyElementsOf(own);
	}

	@Test
	@DisplayName("004-14: vacancy no lee tablas de otros módulos (ninguna consulta ni migración propia las nombra)")
	void t004_14_vacancySqlOnlyTouchesItsOwnTables() throws IOException {
		Pattern foreignTables = anyOf(tablesCreatedBy(name -> !name.contains("vacancy")));
		List<Path> vacancyFiles = new ArrayList<>();
		try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java", "com", "talenti", "vacancy"))) {
			sources.filter(path -> path.toString().endsWith(".java")).forEach(vacancyFiles::add);
		}
		try (Stream<Path> own = Files.list(MIGRATIONS)) {
			own.filter(path -> path.getFileName().toString().contains("vacancy")).forEach(vacancyFiles::add);
		}
		try (Stream<Path> demo = Files.walk(Path.of("src", "main", "resources", "db", "demo"))) {
			demo.filter(path -> path.toString().endsWith(".sql")).forEach(vacancyFiles::add);
		}

		assertThat(vacancyFiles).hasSizeGreaterThan(10);
		for (Path file : vacancyFiles) {
			String text = Files.readString(file);
			assertThat(foreignTables.matcher(text).find()).as("%s nombra una tabla de otro módulo", file).isFalse();
		}
	}

	@Test
	@DisplayName("004-14: ningún otro módulo lee las tablas de vacancy")
	void t004_14_otherModulesDoNotReadVacancyTables() throws IOException {
		Pattern vacancyTables = anyOf(tablesCreatedBy(name -> name.contains("vacancy")));
		try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java", "com", "talenti"))) {
			List<Path> others = sources.filter(path -> path.toString().endsWith(".java"))
					.filter(path -> !path.toString().replace('\\', '/').contains("/talenti/vacancy/")).toList();

			assertThat(others).isNotEmpty();
			for (Path file : others) {
				String text = Files.readString(file);
				// Solo se miran las consultas (texto entre comillas que empieza como SQL), no los comentarios.
				for (String sql : sqlStatements(text)) {
					assertThat(vacancyTables.matcher(sql).find()).as("%s consulta una tabla de vacancy", file).isFalse();
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
