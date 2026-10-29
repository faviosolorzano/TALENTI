package com.talenti.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * 007-17, 015-18 y T5: el módulo matching solo usa la interfaz pública de profile ({@code ProfileReader}), de vacancy
 * ({@code VacancyLookup}, {@code SkillCatalog}), de identity ({@code CurrentUser}) y {@code shared.ai}; no usa nada de
 * practice; nadie más usa sus clases internas; el motor de reglas no conoce a ningún otro módulo, ni a Spring, ni a la
 * base de datos, ni a la IA (007-08); solo su paquete {@code persistence} toca JDBC; y el cupo de IA solo lo usa el
 * servicio de la explicación.
 */
@AnalyzeClasses(packages = "com.talenti", importOptions = ImportOption.DoNotIncludeTests.class)
class MatchingArchitectureTests {

	private static final String MATCHING = "com.talenti.matching..";

	/** Lo que matching puede usar de otros módulos, y nada más (clases anidadas incluidas). */
	private static final Set<String> ALLOWED_FOREIGN = Set.of("com.talenti.identity.CurrentUser",
			"com.talenti.identity.Role", "com.talenti.profile.ProfileReader", "com.talenti.profile.ProfileSnapshot",
			"com.talenti.profile.ProfileSnapshot$Skill", "com.talenti.profile.WorkModality",
			// 013-13: la fuente real del nivel (DECLARED u OBSERVED) solo se muestra, no entra en el cálculo.
			"com.talenti.profile.SkillEvidence",
			"com.talenti.vacancy.VacancyLookup", "com.talenti.vacancy.VacancyInfo",
			"com.talenti.vacancy.VacancyInfo$Modality", "com.talenti.vacancy.VacancyInfo$Status",
			"com.talenti.vacancy.VacancyInfo$RequirementKind", "com.talenti.vacancy.VacancyInfo$Company",
			"com.talenti.vacancy.VacancyInfo$Requirement",
			// 015-07: el catálogo con sinónimos para validar qué habilidades menciona la IA.
			"com.talenti.vacancy.SkillCatalog", "com.talenti.vacancy.SkillInfo");

	@ArchTest
	static void matchingOnlyUsesPublicApiOfOtherModules(JavaClasses classes) {
		Set<String> used = new TreeSet<>();
		for (JavaClass javaClass : classes) {
			if (!javaClass.getPackageName().startsWith("com.talenti.matching")) {
				continue;
			}
			for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
				JavaClass target = dependency.getTargetClass();
				String pkg = target.getPackageName();
				if (!pkg.startsWith("com.talenti.")) {
					continue;
				}
				String module = pkg.substring("com.talenti.".length()).split("[.]")[0];
				if (!module.equals("matching") && !module.equals("shared")) {
					used.add(target.getFullName());
				}
			}
		}

		assertThat(used).as("clases de otros módulos que usa matching").isNotEmpty().isSubsetOf(ALLOWED_FOREIGN);
	}

	@ArchTest
	static void matchingDoesNotUsePractice(JavaClasses classes) {
		noClasses().that().resideInAPackage(MATCHING).should().dependOnClassesThat()
				.resideInAnyPackage("com.talenti.practice..")
				.as("matching no usa nada de practice (T5, 007-17)").check(classes);
	}

	@ArchTest
	static void matchingDoesNotUseInternalsOfOtherModules(JavaClasses classes) {
		noClasses().that().resideInAPackage(MATCHING).should().dependOnClassesThat()
				.resideInAnyPackage("com.talenti.profile.domain..", "com.talenti.profile.persistence..",
						"com.talenti.profile.service..", "com.talenti.profile.web..", "com.talenti.profile.config..",
						"com.talenti.vacancy.domain..", "com.talenti.vacancy.persistence..",
						"com.talenti.vacancy.service..", "com.talenti.vacancy.web..", "com.talenti.vacancy.config..",
						"com.talenti.identity.domain..", "com.talenti.identity.service..", "com.talenti.identity.web..",
						"com.talenti.identity.config..")
				.as("matching no usa clases internas de otros módulos").check(classes);
	}

	@ArchTest
	static void nobodyElseUsesMatchingInternals(JavaClasses classes) {
		noClasses().that().resideOutsideOfPackage(MATCHING).should().dependOnClassesThat()
				.resideInAnyPackage("com.talenti.matching.domain..", "com.talenti.matching.service..",
						"com.talenti.matching.web..", "com.talenti.matching.config..")
				.as("los demás módulos no usan las clases internas de matching").check(classes);
	}

	/** El motor (y la explicación) son código puro: ni Spring, ni JDBC, ni otro módulo, ni IA (007-08). */
	@ArchTest
	static void ruleEngineIsPure(JavaClasses classes) {
		noClasses().that().resideInAPackage("com.talenti.matching.domain..").should().dependOnClassesThat()
				.resideOutsideOfPackages("com.talenti.matching.domain..", "java..")
				.as("el motor de reglas solo usa su paquete y java.*: sin Spring, base de datos, otros módulos ni IA")
				.check(classes);
	}

	@ArchTest
	static void onlyPersistenceUsesJdbc(JavaClasses classes) {
		noClasses().that().resideInAPackage(MATCHING).and().resideOutsideOfPackage("com.talenti.matching.persistence..")
				.should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "javax.sql..")
				.as("en matching solo persistence usa JDBC (su tabla compatibility_explanation, V14)").check(classes);
	}

	/** 015-18: el cupo y el consumo de IA ({@code shared.ai}) solo los usa el servicio; ningún otro puerto de IA. */
	@ArchTest
	static void onlyServiceUsesSharedAi(JavaClasses classes) {
		noClasses().that().resideInAPackage(MATCHING).and().resideOutsideOfPackage("com.talenti.matching.service..")
				.should().dependOnClassesThat().resideInAnyPackage("com.talenti.shared.ai..")
				.as("en matching solo el servicio usa shared.ai").check(classes);
		noClasses().that().resideInAPackage(MATCHING).should().dependOnClassesThat()
				.resideInAnyPackage("com.talenti.practice..", "com.talenti.cv..", "com.talenti.exam..")
				.as("matching tiene su propio puerto de IA: no usa los de practice, cv ni exam (T5)").check(classes);
	}

}
