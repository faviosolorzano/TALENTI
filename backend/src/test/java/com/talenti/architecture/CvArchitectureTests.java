package com.talenti.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
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

import com.talenti.cv.CvExtractionPort;
import com.talenti.cv.service.CvReadService;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * Límites del módulo cv (spec 009, principio 12) y del control de consumo de IA: cv solo usa la interfaz pública de los
 * demás módulos; nadie más usa cv; la IA se invoca desde un único punto; el consumo se mide solo por las interfaces
 * públicas de shared; matching y practice no llaman a la IA ni escriben el consumo.
 */
@AnalyzeClasses(packages = "com.talenti", importOptions = ImportOption.DoNotIncludeTests.class)
class CvArchitectureTests {

	/**
	 * Lo que cv puede usar de otros módulos, y nada más (009 sección 9): el usuario de la sesión, el catálogo de
	 * habilidades y la interfaz pública de profile (su paquete raíz). Sin permiso CV_AI aparte (enmienda 1): no usa
	 * {@code PurposeConsents} ni nada de identity salvo la sesión.
	 */
	private static boolean isAllowedForeign(JavaClass target) {
		String name = target.getFullName();
		return name.equals("com.talenti.identity.CurrentUser") || name.equals("com.talenti.identity.Role")
				|| name.equals("com.talenti.vacancy.SkillCatalog") || name.equals("com.talenti.vacancy.SkillInfo")
				|| target.getPackageName().equals("com.talenti.profile");
	}

	private static boolean isOtherModule(JavaClass target) {
		String pkg = target.getPackageName();
		if (!pkg.startsWith("com.talenti.")) {
			return false;
		}
		String module = pkg.substring("com.talenti.".length()).split("\\.")[0];
		return !module.equals("cv") && !module.equals("shared");
	}

	@ArchTest
	static void cvOnlyUsesPublicApiOfOtherModules(JavaClasses classes) {
		Set<String> used = new TreeSet<>();
		for (JavaClass javaClass : classes) {
			if (!javaClass.getPackageName().startsWith("com.talenti.cv")) {
				continue;
			}
			for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
				JavaClass target = dependency.getTargetClass();
				if (isOtherModule(target)) {
					used.add(target.getFullName());
					assertThat(isAllowedForeign(target)).as("%s usa %s", javaClass.getName(), target.getName()).isTrue();
				}
			}
		}

		assertThat(used).as("clases de otros módulos que usa cv").contains("com.talenti.profile.ProfileEditor",
				"com.talenti.profile.ConfirmedChanges", "com.talenti.vacancy.SkillCatalog",
				"com.talenti.identity.CurrentUser");
	}

	@ArchTest
	static void nobodyElseUsesCv(JavaClasses classes) {
		noClasses().that().resideOutsideOfPackage("com.talenti.cv..").should().dependOnClassesThat()
				.resideInAPackage("com.talenti.cv..")
				.as("ningún otro módulo usa el módulo cv; matching, practice y application no lo conocen")
				.check(classes);
	}

	@ArchTest
	static void matchingAndPracticeDoNotTouchAiUsage(JavaClasses classes) {
		noClasses().that().resideInAnyPackage("com.talenti.matching..", "com.talenti.practice..",
				"com.talenti.application..").should().dependOnClassesThat()
				.resideInAnyPackage("com.talenti.cv..", "com.talenti.shared.ai..")
				.allowEmptyShould(true)
				.as("matching y la compatibilidad no llaman al lector de CV ni escriben el consumo de IA (009-33)")
				.check(classes);
	}

	@ArchTest
	static void aiUsageOnlyThroughPublicInterfaces(JavaClasses classes) {
		noClasses().that().resideOutsideOfPackage("com.talenti.shared.ai..").should()
				.dependOnClassesThat(JavaClass.Predicates.resideInAPackage("com.talenti.shared.ai..")
						.and(JavaClass.Predicates.simpleNameStartingWith("Jdbc")))
				.as("AiUsageQuota y AiUsageRecorder se usan solo por su interfaz pública (009-34)").check(classes);
		noClasses().that().resideOutsideOfPackage("com.talenti.shared.ai..").should().dependOnClassesThat()
				.haveFullyQualifiedName("com.talenti.shared.ai.AiLimitsProperties")
				.as("la configuración de los topes es interna de shared").check(classes);
	}

	@ArchTest
	static void onlyTheReadServiceCallsTheAdapters(JavaClasses classes) {
		noClasses().that().doNotHaveFullyQualifiedName(CvReadService.class.getName()).and()
				.areNotAssignableTo(CvExtractionPort.class).should()
				.callMethodWhere(target(name("extract")).and(target(owner(assignableTo(CvExtractionPort.class)))))
				.as("el adaptador de extracción solo se invoca desde el servicio de lecturas (009-25)").check(classes);
		classes().that().haveFullyQualifiedName(CvReadService.class.getName()).should()
				.callMethodWhere(target(name("extract")).and(target(owner(assignableTo(CvExtractionPort.class)))))
				.as("y ahí sí se invoca").check(classes);
	}

	@ArchTest
	static void onlyTheRepositoryUsesJdbc(JavaClasses classes) {
		noClasses().that().resideInAPackage("com.talenti.cv..").and().resideOutsideOfPackage("com.talenti.cv.persistence..")
				.should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "javax.sql..")
				.as("solo el repositorio de cv toca la base de datos").check(classes);
	}

	@ArchTest
	static void domainDoesNotKnowTheFramework(JavaClasses classes) {
		noClasses().that().resideInAPackage("com.talenti.cv.domain..").should().dependOnClassesThat()
				.resideInAnyPackage("org.springframework..", "jakarta..", "org.apache.pdfbox..", "com.talenti.cv.service..",
						"com.talenti.cv.web..", "com.talenti.cv.persistence..", "com.talenti.cv.adapter..")
				.as("las reglas del dominio del lector son código puro").check(classes);
	}

	@ArchTest
	static void adaptersKnowOnlyThePort(JavaClasses classes) {
		noClasses().that().resideInAPackage("com.talenti.cv.adapter..").should().dependOnClassesThat()
				.resideInAnyPackage("com.talenti.cv.service..", "com.talenti.cv.web..", "com.talenti.cv.persistence..",
						"com.talenti.shared..", "com.talenti.profile..")
				.as("los adaptadores solo conocen el puerto: ni la base de datos, ni el perfil, ni el consumo (principio 8)")
				.check(classes);
	}

	@ArchTest
	static void pdfLibraryOnlyInTheExtractor(JavaClasses classes) {
		noClasses().that().resideOutsideOfPackage("com.talenti.cv.pdf..").should().dependOnClassesThat()
				.resideInAPackage("org.apache.pdfbox..").as("la librería de PDF vive solo en el extractor de texto")
				.check(classes);
	}

	// ------------------------------------------------------------------------------------------ tablas

	private static final Path SOURCES = Path.of("src", "main", "java", "com", "talenti");

	private static final Pattern TABLE_REFERENCE = Pattern
			.compile("(?i)\\b(?:from|join|into|update)\\s+(\\w+)");

	private static boolean isIn(Path path, String module) {
		return path.toString().replace('\\', '/').contains("/talenti/" + module + "/");
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

	private static Set<String> tablesUsedBy(java.util.function.Predicate<Path> files) throws IOException {
		Set<String> tables = new TreeSet<>();
		try (Stream<Path> sources = Files.walk(SOURCES)) {
			for (Path file : sources.filter(path -> path.toString().endsWith(".java")).filter(files).toList()) {
				for (String sql : sqlStatements(Files.readString(file))) {
					Matcher matcher = TABLE_REFERENCE.matcher(sql);
					while (matcher.find()) {
						String table = matcher.group(1).toLowerCase(Locale.ROOT);
						if (table.contains("_")) {
							tables.add(table);
						}
					}
				}
			}
		}
		return tables;
	}

	@Test
	@DisplayName("009-34: cv solo consulta sus dos tablas; el consumo de IA se escribe y se cuenta solo desde shared")
	void t009_34_cvOnlyReadsItsOwnTables() throws IOException {
		assertThat(tablesUsedBy(path -> isIn(path, "cv"))).containsExactlyInAnyOrder("cv_analysis", "cv_suggestion");
		assertThat(tablesUsedBy(path -> isIn(path, "shared") && path.toString().replace('\\', '/').contains("/ai/")))
				.containsExactly("ai_usage_event");
	}

	@Test
	@DisplayName("009-34: ningún otro módulo lee las tablas del lector de CV ni la de consumo de IA")
	void t009_34_otherModulesDoNotReadCvTables() throws IOException {
		Set<String> others = tablesUsedBy(
				path -> !isIn(path, "cv") && !path.toString().replace('\\', '/').contains("/shared/ai/"));

		assertThat(others).doesNotContain("cv_analysis", "cv_suggestion", "ai_usage_event");
	}

}
