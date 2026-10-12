package com.talenti.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.TreeSet;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * 004-14 y principio 12: el módulo vacancy no usa clases de otros módulos, y los demás solo ven su interfaz pública
 * (el paquete raíz: SkillCatalog y SkillInfo). Complementa ArchitectureTests.
 *
 * <p>La lista de módulos ajenos NO se escribe a mano: sale de los paquetes reales de {@code com.talenti} (todo
 * paquete de primer nivel salvo vacancy y la base común shared). Un módulo nuevo queda cubierto sin tocar esta
 * prueba.
 */
@AnalyzeClasses(packages = "com.talenti", importOptions = ImportOption.DoNotIncludeTests.class)
class VacancyArchitectureTests {

	private static final String BASE = "com.talenti.";

	/** Módulos de primer nivel de com.talenti, menos vacancy y shared. */
	static Set<String> otherModules(JavaClasses classes) {
		Set<String> modules = new TreeSet<>();
		for (JavaClass javaClass : classes) {
			String pkg = javaClass.getPackageName();
			if (pkg.startsWith(BASE)) {
				String module = pkg.substring(BASE.length()).split("\\.")[0];
				if (!module.equals("vacancy") && !module.equals("shared")) {
					modules.add(module);
				}
			}
		}
		return modules;
	}

	private static String[] packagesOf(Set<String> modules) {
		return modules.stream().map(module -> BASE + module + "..").toArray(String[]::new);
	}

	@ArchTest
	static void otherModulesAreDerivedFromRealPackages(JavaClasses classes) {
		assertThat(otherModules(classes)).as("módulos ajenos a vacancy encontrados en com.talenti")
				.contains("identity", "profile", "matching", "application", "practice");
	}

	@ArchTest
	static void vacancyDoesNotUseOtherModules(JavaClasses classes) {
		noClasses().that().resideInAPackage("com.talenti.vacancy..").should().dependOnClassesThat()
				.resideInAnyPackage(packagesOf(otherModules(classes)))
				.as("vacancy no usa clases de otros módulos (004-14)").check(classes);
	}

	@ArchTest
	static void otherModulesOnlyUseVacancyPublicApi(JavaClasses classes) {
		String[] others = packagesOf(otherModules(classes));
		String[] shared = { "com.talenti.shared.." };
		String[] callers = new String[others.length + 1];
		System.arraycopy(others, 0, callers, 0, others.length);
		callers[others.length] = shared[0];

		noClasses().that().resideInAnyPackage(callers).should().dependOnClassesThat()
				.resideInAnyPackage("com.talenti.vacancy.domain..", "com.talenti.vacancy.persistence..",
						"com.talenti.vacancy.service..", "com.talenti.vacancy.web..", "com.talenti.vacancy.config..")
				.as("los demás módulos solo usan la interfaz pública de vacancy (paquete raíz), no sus clases internas")
				.check(classes);
	}

}
