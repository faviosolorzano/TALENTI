package com.talenti.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.practice.InterviewResults;
import com.talenti.practice.port.LlmPort;
import com.talenti.practice.port.SpeechToTextPort;
import com.talenti.practice.port.TextToSpeechPort;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * Límites del módulo practice (spec 012, principio 12, T5): solo usa las interfaces públicas permitidas; nadie lee las
 * sesiones de práctica (la única interfaz pública es {@link InterviewResults}, que solo trae entrevistas); el dominio es
 * código puro; el servicio conversa solo por {@code ConversationPort} y nunca conoce a un proveedor (principio 8); y
 * solo el repositorio toca la base de datos y sus propias tablas.
 */
@AnalyzeClasses(packages = "com.talenti", importOptions = ImportOption.DoNotIncludeTests.class)
class PracticeArchitectureTests {

	private static final Set<String> ALLOWED_FOREIGN = Set.of("com.talenti.identity.CurrentUser",
			"com.talenti.identity.Role", "com.talenti.profile.ProfileReader", "com.talenti.profile.ProfileSnapshot",
			"com.talenti.profile.ProfileSnapshot$Skill", "com.talenti.profile.ProfileSnapshot$Project",
			"com.talenti.profile.ProfileFirstName", "com.talenti.vacancy.VacancyLookup", "com.talenti.vacancy.VacancyInfo",
			"com.talenti.vacancy.VacancyInfo$Requirement", "com.talenti.vacancy.VacancyInfo$Company",
			"com.talenti.vacancy.SkillCatalog", "com.talenti.vacancy.SkillInfo", "com.talenti.application.ApplicationStages",
			"com.talenti.application.ApplicationStages$Owned", "com.talenti.application.ApplicationStages$Stage");

	@ArchTest
	static void practiceOnlyUsesPublicApiOfOtherModules(JavaClasses classes) {
		Set<String> used = new TreeSet<>();
		for (JavaClass javaClass : classes) {
			if (!javaClass.getPackageName().startsWith("com.talenti.practice")) {
				continue;
			}
			for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
				String pkg = dependency.getTargetClass().getPackageName();
				if (pkg.startsWith("com.talenti.") && !pkg.startsWith("com.talenti.practice")
						&& !pkg.startsWith("com.talenti.shared")) {
					used.add(dependency.getTargetClass().getFullName());
				}
			}
		}

		assertThat(used).as("clases de otros módulos que usa practice (012, sección 8)")
				.contains("com.talenti.application.ApplicationStages", "com.talenti.profile.ProfileFirstName")
				.isSubsetOf(ALLOWED_FOREIGN);
	}

	/** 012-22 (T5): ningún otro módulo usa practice salvo su única interfaz pública, que solo trae entrevistas. */
	@ArchTest
	static void nobodyReadsPracticeSessions(JavaClasses classes) {
		noClasses().that().resideOutsideOfPackage("com.talenti.practice..").should().dependOnClassesThat(
				JavaClass.Predicates.resideInAPackage("com.talenti.practice..")
						.and(DescribedPredicate.not(JavaClass.Predicates.belongToAnyOf(InterviewResults.class))))
				.as("ningún módulo lee sesiones de práctica (012-22)").check(classes);
		noClasses().that().resideInAPackage("com.talenti.application..").should().dependOnClassesThat()
				.resideInAPackage("com.talenti.practice..").as("application no conoce practice").check(classes);
	}

	@Test
	@DisplayName("012-22: la única interfaz pública de practice es InterviewResults, y solo habla de entrevistas")
	void t012_22_onlyInterviewResultsIsPublic() {
		JavaClasses root = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
				.importPackages("com.talenti.practice");
		List<String> rootTypes = new ArrayList<>();
		for (JavaClass javaClass : root) {
			if (javaClass.getPackageName().equals("com.talenti.practice") && !javaClass.isNestedClass()
					&& !javaClass.getSimpleName().equals("package-info")) {
				rootTypes.add(javaClass.getSimpleName());
			}
		}

		assertThat(rootTypes).containsExactly("InterviewResults");
		assertThat(InterviewResults.class.getMethods()).extracting(java.lang.reflect.Method::getName)
				.containsExactly("forApplication");
		for (Class<?> nested : InterviewResults.class.getDeclaredClasses()) {
			assertThat(Modifier.isPublic(nested.getModifiers())).isTrue();
			assertThat(nested.getSimpleName().toLowerCase(Locale.ROOT)).doesNotContain("practice");
		}
	}

	@ArchTest
	static void domainIsPure(JavaClasses classes) {
		noClasses().that().resideInAPackage("com.talenti.practice.domain..").should().dependOnClassesThat()
				.resideOutsideOfPackages("com.talenti.practice.domain..", "java..")
				.as("el dominio de DORA es código puro").check(classes);
	}

	/** 012-31 y principio 8: el servicio solo conversa por ConversationPort (y LlmPort para planificar y evaluar). */
	@ArchTest
	static void serviceKnowsOnlyThePorts(JavaClasses classes) {
		noClasses().that().resideInAnyPackage("com.talenti.practice.service..", "com.talenti.practice.web..",
				"com.talenti.practice.domain..").should().dependOnClassesThat()
				.resideInAPackage("com.talenti.practice.adapter..")
				.as("el servicio no conoce a ningún proveedor ni a los simulados").check(classes);
		noClasses().that().resideInAnyPackage("com.talenti.practice.service..", "com.talenti.practice.web..").should()
				.dependOnClassesThat().belongToAnyOf(SpeechToTextPort.class, TextToSpeechPort.class)
				.as("la voz va detrás del adaptador por turnos de ConversationPort").check(classes);
		noClasses().that().resideInAPackage("com.talenti.practice.web..").should().dependOnClassesThat()
				.belongToAnyOf(LlmPort.class).as("la web no llama a la IA").check(classes);
	}

	@ArchTest
	static void adaptersKnowOnlyThePorts(JavaClasses classes) {
		noClasses().that().resideInAPackage("com.talenti.practice.adapter..").should().dependOnClassesThat()
				.resideInAnyPackage("com.talenti.practice.service..", "com.talenti.practice.web..",
						"com.talenti.practice.persistence..", "com.talenti.shared..", "com.talenti.profile..",
						"com.talenti.application..", "org.springframework.jdbc..")
				.as("los adaptadores solo conocen los puertos (principio 8)").check(classes);
	}

	@ArchTest
	static void onlyTheRepositoryUsesJdbc(JavaClasses classes) {
		noClasses().that().resideInAPackage("com.talenti.practice..").and()
				.resideOutsideOfPackage("com.talenti.practice.persistence..").should().dependOnClassesThat()
				.resideInAnyPackage("org.springframework.jdbc..", "javax.sql..")
				.as("solo el repositorio de practice toca la base de datos").check(classes);
	}

	// ------------------------------------------------------------------------------------------ tablas

	private static final Path SOURCES = Path.of("src", "main", "java", "com", "talenti");

	private static final Pattern TABLE = Pattern.compile("(?i)\\b(?:from|join|into|update)\\s+(\\w+)");

	private static final Set<String> OWN = Set.of("practice_session", "practice_question", "practice_turn",
			"practice_feedback", "interview_event");

	@Test
	@DisplayName("012-22 y principio 12: practice solo consulta sus tablas y ningún otro módulo las lee")
	void t012_22_tables() throws IOException {
		Set<String> practiceTables = new TreeSet<>();
		Set<String> othersTouching = new TreeSet<>();
		Pattern sql = Pattern.compile("\"\"\"(.*?)\"\"\"|\"((?:SELECT|INSERT|UPDATE|DELETE|WITH)\\b[^\"]*)\"",
				Pattern.DOTALL);
		Pattern fragment = Pattern.compile("\"([^\"]*\\b(?:FROM|JOIN|INTO|UPDATE)\\b[^\"]*)\"", Pattern.CASE_INSENSITIVE);
		try (Stream<Path> files = Files.walk(SOURCES)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
				boolean practice = file.toString().replace('\\', '/').contains("/talenti/practice/");
				String source = Files.readString(file);
				List<String> statements = new ArrayList<>();
				Matcher matcher = sql.matcher(source);
				while (matcher.find()) {
					statements.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
				}
				Matcher pieces = fragment.matcher(source);
				while (pieces.find()) {
					statements.add(pieces.group(1));
				}
				for (String statement : statements) {
					Matcher table = TABLE.matcher(statement);
					while (table.find()) {
						String name = table.group(1).toLowerCase(Locale.ROOT);
						if (!name.contains("_")) {
							continue;
						}
						if (practice) {
							practiceTables.add(name);
						}
						else if (OWN.contains(name)) {
							othersTouching.add(file.getFileName() + ":" + name);
						}
					}
				}
			}
		}

		assertThat(practiceTables).isNotEmpty().isSubsetOf(OWN);
		assertThat(othersTouching).isEmpty();
	}

}
