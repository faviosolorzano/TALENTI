package com.talenti.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;

import com.talenti.application.ApplicationStages;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * 008-12 y principio 12: {@code application} no depende de {@code practice} (T5) y {@code practice} solo usa su
 * interfaz pública {@code ApplicationStages} (spec 012); de los demás módulos solo
 * usa {@code VacancyLookup} (con {@code VacancyInfo}) e {@code identity.CurrentUser}; nadie usa sus clases internas; y
 * solo su repositorio toca la base de datos.
 */
@AnalyzeClasses(packages = "com.talenti", importOptions = ImportOption.DoNotIncludeTests.class)
class ApplicationArchitectureTests {

	private static final Set<String> ALLOWED_FOREIGN = Set.of("com.talenti.identity.CurrentUser",
			"com.talenti.identity.Role", "com.talenti.vacancy.VacancyLookup", "com.talenti.vacancy.VacancyInfo");

	@ArchTest
	static void applicationAndPracticeKnowNothingOfEachOther(JavaClasses classes) {
		noClasses().that().resideInAPackage("com.talenti.application..").should().dependOnClassesThat()
				.resideInAPackage("com.talenti.practice..").allowEmptyShould(true)
				.as("application no depende de practice (T5)").check(classes);
		// Spec 012: practice solo usa la interfaz pública ApplicationStages (etapas de la entrevista evaluada).
		noClasses().that().resideInAPackage("com.talenti.practice..").should().dependOnClassesThat(
				JavaClass.Predicates.resideInAPackage("com.talenti.application..")
						.and(DescribedPredicate.not(JavaClass.Predicates.belongToAnyOf(ApplicationStages.class))))
				.allowEmptyShould(true)
				.as("practice solo usa ApplicationStages de application (spec 012)").check(classes);
	}

	@ArchTest
	static void applicationOnlyUsesVacancyLookupAndTheSessionUser(JavaClasses classes) {
		Set<String> used = new TreeSet<>();
		for (JavaClass javaClass : classes) {
			if (!javaClass.getPackageName().startsWith("com.talenti.application")) {
				continue;
			}
			for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
				String pkg = dependency.getTargetClass().getPackageName();
				if (pkg.startsWith("com.talenti.") && !pkg.startsWith("com.talenti.application")
						&& !pkg.startsWith("com.talenti.shared")) {
					used.add(dependency.getTargetClass().getFullName().split("[$]")[0]);
				}
			}
		}

		assertThat(used).as("clases de otros módulos que usa application").contains("com.talenti.vacancy.VacancyLookup",
				"com.talenti.identity.CurrentUser").isSubsetOf(ALLOWED_FOREIGN);
	}

	@ArchTest
	static void nobodyElseUsesApplicationInternals(JavaClasses classes) {
		noClasses().that().resideOutsideOfPackage("com.talenti.application..").should().dependOnClassesThat(
				JavaClass.Predicates.resideInAPackage("com.talenti.application..")
						.and(DescribedPredicate.not(JavaClass.Predicates.belongToAnyOf(ApplicationStages.class))))
				.allowEmptyShould(true)
				.as("ningún otro módulo usa application, salvo su interfaz pública ApplicationStages").check(classes);
		noClasses().that().resideOutsideOfPackages("com.talenti.application..", "com.talenti.practice..").should()
				.dependOnClassesThat().belongToAnyOf(ApplicationStages.class).allowEmptyShould(true)
				.as("solo practice usa ApplicationStages (spec 012)").check(classes);
	}

	@ArchTest
	static void onlyTheRepositoryUsesJdbc(JavaClasses classes) {
		noClasses().that().resideInAPackage("com.talenti.application..").and()
				.resideOutsideOfPackage("com.talenti.application.persistence..").should().dependOnClassesThat()
				.resideInAnyPackage("org.springframework.jdbc..", "javax.sql..")
				.as("solo el repositorio de application toca la base de datos").check(classes);
	}

}
