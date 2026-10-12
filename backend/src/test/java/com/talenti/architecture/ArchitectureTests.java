package com.talenti.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Principio 12 (monolito modular): cada módulo usa solo la interfaz pública de los demás. La interfaz pública de
 * un módulo es la raíz de su paquete (por ejemplo {@code com.talenti.identity.CurrentUser}); lo que está en sus
 * subpaquetes es interno. {@code shared} es la base: no depende de ningún módulo.
 */
@AnalyzeClasses(packages = "com.talenti", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTests {

	private static final String[] OTHER_MODULES = { "com.talenti.profile..", "com.talenti.vacancy..",
			"com.talenti.matching..", "com.talenti.application..", "com.talenti.practice.." };

	@ArchTest
	static final ArchRule identityDoesNotUseOtherModules = noClasses().that().resideInAPackage("com.talenti.identity..")
			.should().dependOnClassesThat().resideInAnyPackage(OTHER_MODULES)
			.as("identity no usa clases de otros módulos");

	@ArchTest
	static final ArchRule otherModulesDoNotUseIdentityInternals = noClasses().that().resideInAnyPackage(OTHER_MODULES)
			.should().dependOnClassesThat().resideInAnyPackage("com.talenti.identity.domain..",
					"com.talenti.identity.service..", "com.talenti.identity.web..", "com.talenti.identity.config..")
			.allowEmptyShould(true)
			.as("los demás módulos no usan clases internas de identity (solo su paquete raíz)");

	@ArchTest
	static final ArchRule sharedDoesNotDependOnModules = noClasses().that().resideInAPackage("com.talenti.shared..")
			.should().dependOnClassesThat().resideInAnyPackage("com.talenti.identity..", "com.talenti.profile..",
					"com.talenti.vacancy..", "com.talenti.matching..", "com.talenti.application..",
					"com.talenti.practice..")
			.as("shared no depende de ningún módulo");

	@ArchTest
	static final ArchRule identityDoesNotUseJdbcDirectly = noClasses().that().resideInAPackage("com.talenti.identity..")
			.should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "javax.sql..")
			.as("identity no usa JDBC directo: la auditoría va por AuditRecorder y no escribe en audit_event");

	@ArchTest
	static final ArchRule auditImplementationIsInternal = noClasses().that()
			.resideOutsideOfPackage("com.talenti.shared.audit..").should().dependOnClassesThat()
			.haveSimpleName("JdbcAuditRecorder")
			.allowEmptyShould(true)
			.as("la auditoría se usa solo por la interfaz AuditRecorder");

}
