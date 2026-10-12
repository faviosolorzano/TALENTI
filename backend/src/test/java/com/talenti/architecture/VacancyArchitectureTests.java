package com.talenti.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * 004-14 y principio 12: el módulo vacancy no usa clases ni tablas de otros módulos, y los demás solo ven su
 * interfaz pública (el paquete raíz: SkillCatalog y SkillInfo). Complementa ArchitectureTests, cuya regla de
 * "los módulos no dependen unos de otros" ya cubre a vacancy sin excepciones.
 */
@AnalyzeClasses(packages = "com.talenti", importOptions = ImportOption.DoNotIncludeTests.class)
class VacancyArchitectureTests {

	@ArchTest
	static final ArchRule vacancyDoesNotUseOtherModules = noClasses().that().resideInAPackage("com.talenti.vacancy..")
			.should().dependOnClassesThat().resideInAnyPackage("com.talenti.identity..", "com.talenti.profile..",
					"com.talenti.matching..", "com.talenti.application..", "com.talenti.practice..")
			.as("vacancy no usa clases de otros módulos (004-14)");

	@ArchTest
	static final ArchRule otherModulesOnlyUseVacancyPublicApi = noClasses().that()
			.resideInAnyPackage("com.talenti.identity..", "com.talenti.profile..", "com.talenti.matching..",
					"com.talenti.application..", "com.talenti.practice..", "com.talenti.shared..")
			.should().dependOnClassesThat().resideInAnyPackage("com.talenti.vacancy.domain..",
					"com.talenti.vacancy.persistence..", "com.talenti.vacancy.service..", "com.talenti.vacancy.web..",
					"com.talenti.vacancy.config..")
			.as("los demás módulos solo usan la interfaz pública de vacancy (paquete raíz), no sus clases internas");

}
