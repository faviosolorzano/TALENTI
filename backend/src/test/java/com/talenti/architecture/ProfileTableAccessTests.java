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
 * 006-13 y principio 12: ningún otro módulo lee las tablas de profile, y profile no lee las de los demás (el
 * catálogo de habilidades se obtiene por {@code SkillCatalog}, nunca de la tabla {@code skill}). Revisa el texto de
 * las consultas (Maven ejecuta las pruebas con backend/ como directorio de trabajo). Las listas de tablas salen de
 * los {@code CREATE TABLE} de las migraciones reales.
 */
class ProfileTableAccessTests {

	private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

	private static final Path SOURCES = Path.of("src", "main", "java", "com", "talenti");

	private static final Pattern CREATE_TABLE = Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)",
			Pattern.CASE_INSENSITIVE);

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

	private static boolean isProfileMigration(String fileName) {
		return fileName.contains("student_profile");
	}

	private static boolean isProfileSource(Path path) {
		return path.toString().replace('\\', '/').contains("/talenti/profile/");
	}

	/** El texto de las consultas SQL de un archivo Java (cadenas y bloques de texto que empiezan como SQL). */
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

	@Test
	@DisplayName("006-13: las tablas de profile salen de la migración V5 y son exactamente las tres de la spec")
	void t006_13_profileTablesComeFromItsMigration() throws IOException {
		Set<String> own = tablesCreatedBy(ProfileTableAccessTests::isProfileMigration);
		Set<String> foreign = tablesCreatedBy(name -> !isProfileMigration(name));

		assertThat(own).containsExactlyInAnyOrder("student_profile", "student_skill", "student_project");
		assertThat(foreign).contains("app_user", "skill").doesNotContainAnyElementsOf(own);
	}

	@Test
	@DisplayName("006-13: ningún otro módulo lee las tablas de profile")
	void t006_13_otherModulesDoNotReadProfileTables() throws IOException {
		Pattern profileTables = anyOf(tablesCreatedBy(ProfileTableAccessTests::isProfileMigration));
		try (Stream<Path> sources = Files.walk(SOURCES)) {
			List<Path> others = sources.filter(path -> path.toString().endsWith(".java"))
					.filter(path -> !isProfileSource(path)).toList();

			assertThat(others).isNotEmpty();
			for (Path file : others) {
				for (String sql : sqlStatements(Files.readString(file))) {
					assertThat(profileTables.matcher(sql).find()).as("%s consulta una tabla de profile", file).isFalse();
				}
			}
		}
	}

	@Test
	@DisplayName("006-13: profile solo consulta sus propias tablas (ni app_user ni skill ni ninguna otra)")
	void t006_13_profileOnlyReadsItsOwnTables() throws IOException {
		Pattern foreignTables = anyOf(tablesCreatedBy(name -> !isProfileMigration(name)));
		List<Path> profileFiles;
		try (Stream<Path> sources = Files.walk(SOURCES)) {
			profileFiles = sources.filter(path -> path.toString().endsWith(".java")).filter(ProfileTableAccessTests::isProfileSource)
					.toList();
		}

		assertThat(profileFiles).hasSizeGreaterThan(10);
		int statements = 0;
		for (Path file : profileFiles) {
			for (String sql : sqlStatements(Files.readString(file))) {
				statements++;
				assertThat(foreignTables.matcher(sql).find()).as("%s consulta una tabla de otro módulo", file).isFalse();
			}
		}
		assertThat(statements).as("consultas SQL encontradas en profile").isGreaterThan(10);
	}

}
