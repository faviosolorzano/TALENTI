package com.talenti.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * 013-16 y principio 12: github solo usa {@code SkillCatalog} y {@code SkillEvidenceWriter} (más el usuario de la
 * sesión y el perfil que devuelve la confirmación); nadie usa sus clases internas (solo su interfaz pública
 * {@code GithubShowcase}); solo su repositorio toca la base; su dominio es código puro; y ningún otro módulo lee sus
 * tablas, ni github lee las de otros.
 */
@AnalyzeClasses(packages = "com.talenti", importOptions = ImportOption.DoNotIncludeTests.class)
class GithubArchitectureTests {

	private static final String GITHUB = "com.talenti.github..";

	private static final Set<String> ALLOWED_FOREIGN = Set.of("com.talenti.identity.CurrentUser",
			"com.talenti.identity.Role", "com.talenti.vacancy.SkillCatalog", "com.talenti.vacancy.SkillInfo",
			"com.talenti.profile.SkillEvidenceWriter", "com.talenti.profile.SkillEvidenceWriter$CurrentSkill",
			"com.talenti.profile.SkillEvidenceWriter$ObservedSkill", "com.talenti.profile.SkillEvidence",
			"com.talenti.profile.ProfileView");

	private static final Path MIGRATIONS = Path.of("src", "main", "resources", "db", "migration");

	private static final Path SOURCES = Path.of("src", "main", "java", "com", "talenti");

	@ArchTest
	@DisplayName("013-16: github solo usa SkillCatalog, SkillEvidenceWriter, el usuario de la sesión y el perfil que devuelve")
	static void t013_16_githubOnlyUsesAllowedApis(JavaClasses classes) {
		Set<String> used = new TreeSet<>();
		for (JavaClass javaClass : classes) {
			if (!javaClass.getPackageName().startsWith("com.talenti.github")) {
				continue;
			}
			for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
				String pkg = dependency.getTargetClass().getPackageName();
				if (pkg.startsWith("com.talenti.") && !pkg.startsWith("com.talenti.github")
						&& !pkg.startsWith("com.talenti.shared")) {
					used.add(dependency.getTargetClass().getFullName());
				}
			}
		}

		assertThat(used).as("clases de otros módulos que usa github").contains("com.talenti.vacancy.SkillCatalog",
				"com.talenti.profile.SkillEvidenceWriter").isSubsetOf(ALLOWED_FOREIGN);
	}

	@ArchTest
	@DisplayName("013-16: los demás módulos solo pueden usar GithubShowcase (paquete raíz), nunca las clases internas de github")
	static void t013_16_nobodyUsesGithubInternals(JavaClasses classes) {
		noClasses().that().resideOutsideOfPackage(GITHUB).should().dependOnClassesThat()
				.resideInAnyPackage("com.talenti.github.client..", "com.talenti.github.config..",
						"com.talenti.github.domain..", "com.talenti.github.persistence..", "com.talenti.github.service..",
						"com.talenti.github.web..")
				.allowEmptyShould(true).as("nadie usa las clases internas de github").check(classes);
	}

	@ArchTest
	@DisplayName("013-16: solo el repositorio de github toca la base de datos")
	static void t013_16_onlyGithubStoreUsesJdbc(JavaClasses classes) {
		noClasses().that().resideInAPackage(GITHUB).and().resideOutsideOfPackage("com.talenti.github.persistence..")
				.should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "javax.sql..")
				.as("solo GithubStore toca la base de datos").check(classes);
	}

	@ArchTest
	@DisplayName("013-10: la detección y los porcentajes son código puro (sin Spring, base de datos, otros módulos ni IA)")
	static void t013_10_domainIsPure(JavaClasses classes) {
		noClasses().that().resideInAPackage("com.talenti.github.domain..").should().dependOnClassesThat()
				.resideOutsideOfPackages("com.talenti.github.domain..", "java..")
				.as("el dominio de github solo usa su paquete y java.*").check(classes);
		noClasses().that().resideInAPackage(GITHUB).should().dependOnClassesThat()
				.resideInAnyPackage("com.talenti.shared.ai..", "com.talenti.cv..", "com.talenti.practice..",
						"com.talenti.matching..", "com.talenti.application..")
				.as("github no usa IA ni otros módulos").check(classes);
	}

	// ------------------------------------------------------------------------------------------ tablas (texto SQL)

	private static Set<String> tablesCreatedBy(java.util.function.Predicate<String> fileFilter) throws IOException {
		Pattern create = Pattern.compile("CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(\\w+)", Pattern.CASE_INSENSITIVE);
		Set<String> tables = new LinkedHashSet<>();
		try (Stream<Path> files = Files.list(MIGRATIONS)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".sql"))
					.filter(path -> fileFilter.test(path.getFileName().toString())).toList()) {
				Matcher matcher = create.matcher(Files.readString(file));
				while (matcher.find()) {
					tables.add(matcher.group(1).toLowerCase(Locale.ROOT));
				}
			}
		}
		return tables;
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

	private static boolean isGithubSource(Path path) {
		return path.toString().replace('\\', '/').contains("/talenti/github/");
	}

	@ArchTest
	@DisplayName("013-16: las tablas de github son las tres de la V10; ningún otro módulo las lee y github no lee las de otros")
	static void t013_16_tableAccess(JavaClasses classes) throws IOException {
		Set<String> own = tablesCreatedBy(name -> name.contains("github"));
		Set<String> foreign = tablesCreatedBy(name -> !name.contains("github"));
		assertThat(own).containsExactlyInAnyOrder("github_link", "github_oauth_state", "github_repository");
		Pattern ownTables = Pattern.compile("\\b(" + String.join("|", own) + ")\\b", Pattern.CASE_INSENSITIVE);
		Pattern foreignTables = Pattern.compile("\\b(" + String.join("|", foreign) + ")\\b", Pattern.CASE_INSENSITIVE);
		int githubStatements = 0;
		try (Stream<Path> sources = Files.walk(SOURCES)) {
			for (Path file : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
				for (String sql : sqlStatements(Files.readString(file))) {
					if (isGithubSource(file)) {
						githubStatements++;
						assertThat(foreignTables.matcher(sql).find()).as("%s consulta una tabla de otro módulo", file)
								.isFalse();
					}
					else {
						assertThat(ownTables.matcher(sql).find()).as("%s consulta una tabla de github", file).isFalse();
					}
				}
			}
		}
		assertThat(githubStatements).isGreaterThan(10);
	}

}
