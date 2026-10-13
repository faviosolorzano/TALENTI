package com.talenti.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.talenti.profile.ProfileReader;
import com.talenti.profile.ProfileSnapshot;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

/**
 * 006-13 y principio 12: el módulo profile solo usa la interfaz pública de identity (el usuario de la sesión) y de
 * vacancy ({@code SkillCatalog}); nadie más usa sus clases internas; y {@code ProfileReader} no entrega datos
 * prohibidos (correo, carrera, instituto, ciclo).
 */
@AnalyzeClasses(packages = "com.talenti", importOptions = ImportOption.DoNotIncludeTests.class)
class ProfileArchitectureTests {

	private static final String PROFILE = "com.talenti.profile..";

	/** Lo que profile puede usar de otros módulos, y nada más (006, sección 8, principio 12). */
	private static final Set<String> ALLOWED_FOREIGN = Set.of("com.talenti.identity.CurrentUser",
			"com.talenti.identity.Role", "com.talenti.vacancy.SkillCatalog", "com.talenti.vacancy.SkillInfo");

	@ArchTest
	static void profileOnlyUsesPublicApiOfOtherModules(JavaClasses classes) {
		Set<String> used = new TreeSet<>();
		for (JavaClass javaClass : classes) {
			if (!javaClass.getPackageName().startsWith("com.talenti.profile")) {
				continue;
			}
			for (Dependency dependency : javaClass.getDirectDependenciesFromSelf()) {
				String target = dependency.getTargetClass().getFullName();
				if (isOtherModule(dependency.getTargetClass())) {
					used.add(target);
				}
			}
		}

		assertThat(used).as("clases de otros módulos que usa profile").isNotEmpty().isSubsetOf(ALLOWED_FOREIGN);
	}

	private static boolean isOtherModule(JavaClass target) {
		String pkg = target.getPackageName();
		if (!pkg.startsWith("com.talenti.")) {
			return false;
		}
		String module = pkg.substring("com.talenti.".length()).split("\\.")[0];
		return !module.equals("profile") && !module.equals("shared");
	}

	@ArchTest
	static void profileDoesNotUseCatalogImplementation(JavaClasses classes) {
		noClasses().that().resideInAPackage(PROFILE).should().dependOnClassesThat()
				.resideInAnyPackage("com.talenti.vacancy.persistence..", "com.talenti.vacancy.domain..",
						"com.talenti.vacancy.service..", "com.talenti.vacancy.web..", "com.talenti.vacancy.config..",
						"com.talenti.identity.domain..", "com.talenti.identity.service..", "com.talenti.identity.web..",
						"com.talenti.identity.config..")
				.as("profile no usa clases internas de identity ni de vacancy (solo SkillCatalog, SkillInfo y CurrentUser)")
				.check(classes);
	}

	@ArchTest
	static void nobodyElseUsesProfileInternals(JavaClasses classes) {
		noClasses().that().resideOutsideOfPackage(PROFILE).should().dependOnClassesThat()
				.resideInAnyPackage("com.talenti.profile.domain..", "com.talenti.profile.persistence..",
						"com.talenti.profile.service..", "com.talenti.profile.web..", "com.talenti.profile.config..")
				.as("los demás módulos solo usan la interfaz pública de profile (paquete raíz: ProfileReader, ProfileEditor y sus tipos)")
				.check(classes);
	}

	@ArchTest
	static void profileRepositoryIsTheOnlyJdbcUser(JavaClasses classes) {
		noClasses().that().resideInAPackage(PROFILE).and().resideOutsideOfPackage("com.talenti.profile.persistence..")
				.should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "javax.sql..")
				.as("solo el repositorio de profile toca la base de datos (006-13)").check(classes);
	}

	// ------------------------------------------------------------------------------------------ ProfileReader

	@ArchTest
	static void profileReaderOnlyReturnsSnapshotAndCompleteness(JavaClasses classes) {
		List<String> returned = new ArrayList<>();
		for (Method method : ProfileReader.class.getMethods()) {
			returned.add(method.getReturnType().getSimpleName());
		}

		assertThat(returned).containsExactlyInAnyOrder("ProfileSnapshot", "ProfileCompleteness");
	}

	@ArchTest
	static void profileSnapshotHasNoForbiddenData(JavaClasses classes) {
		List<String> names = new ArrayList<>();
		collect(ProfileSnapshot.class, names);

		assertThat(names).as("campos del snapshot (con los de sus tipos anidados)")
				.contains("skills", "hoursPerWeek", "acceptedModalities", "location", "projects", "technologies", "slug",
						"level");
		// Dentro de un proyecto "name" y "description" son propios del proyecto (006-13 los pide); lo prohibido es
		// lo personal y lo académico.
		for (String forbidden : List.of("email", "career", "institute", "cycle", "password", "userid", "role")) {
			assertThat(names).as("el snapshot no trae %s", forbidden).doesNotContain(forbidden);
		}
	}

	private static void collect(Class<?> type, List<String> names) {
		for (RecordComponent component : type.getRecordComponents()) {
			names.add(component.getName());
			Class<?> componentType = component.getType();
			if (component.getGenericType() instanceof java.lang.reflect.ParameterizedType parameterized) {
				for (java.lang.reflect.Type argument : parameterized.getActualTypeArguments()) {
					if (argument instanceof Class<?> argumentClass && argumentClass.isRecord()) {
						collect(argumentClass, names);
					}
				}
			}
			if (componentType.isRecord()) {
				collect(componentType, names);
			}
		}
	}

}
