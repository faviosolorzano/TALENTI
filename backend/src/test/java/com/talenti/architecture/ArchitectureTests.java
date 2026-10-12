package com.talenti.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Principio 12 (monolito modular). Cada paquete directo de {@code com.talenti} es un módulo (identity, profile,
 * vacancy, matching, application, practice) o la base común (shared). Hoy ningún módulo puede depender de otro:
 * solo pueden usar {@code shared}, y {@code shared} no puede depender de ninguno. Cuando un módulo necesite la
 * interfaz pública de otro, se agrega aquí una excepción explícita hacia el paquete raíz de ese módulo (por
 * ejemplo {@code com.talenti.identity.CurrentUser}), nunca hacia sus subpaquetes internos.
 */
@AnalyzeClasses(packages = "com.talenti", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

	@ArchTest
	static final ArchRule modulesDoNotDependOnEachOther = slices().matching("com.talenti.(*)..")
			.should().notDependOnEachOther()
			// Excepción explícita: todos los módulos pueden usar shared (errores, auditoría, seguridad).
			.ignoreDependency(alwaysTrue(), resideInAPackage("com.talenti.shared.."))
			.as("los módulos no dependen unos de otros; solo usan shared, y shared no depende de ningún módulo");

	@ArchTest
	static final ArchRule identityDoesNotUseJdbcDirectly = noClasses().that().resideInAPackage("com.talenti.identity..")
			.should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "javax.sql..")
			.as("identity no usa JDBC directo: la auditoría va por AuditRecorder y no escribe en audit_event");

}
