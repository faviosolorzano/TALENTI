package com.talenti.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.*;
import java.util.Set;

@AnalyzeClasses(packages="com.talenti",importOptions=ImportOption.DoNotIncludeTests.class)
class ExamArchitectureTests {
	@ArchTest static void nobodyUsesExamInternals(JavaClasses classes) {
		noClasses().that().resideOutsideOfPackage("com.talenti.exam..")
				.should().dependOnClassesThat().resideInAnyPackage("com.talenti.exam.adapter..","com.talenti.exam.config..",
						"com.talenti.exam.domain..","com.talenti.exam.persistence..","com.talenti.exam.service..","com.talenti.exam.port..","com.talenti.exam.web..","com.talenti.exam.snapshot..")
				.allowEmptyShould(true).check(classes);
	}
	@ArchTest static void otherModulesNeverReadOrWriteExamTables(JavaClasses classes) throws java.io.IOException {
		var sql=java.util.regex.Pattern.compile("(?i)\\b(?:from|join|update|into)\\s+(exam_[a-z_]+)\\b");
		try(var files=java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java/com/talenti"))) {
			for(var path:files.filter(p->p.toString().endsWith(".java")).filter(p->!p.startsWith(java.nio.file.Path.of("src/main/java/com/talenti/exam"))).toList())
				assertThat(sql.matcher(java.nio.file.Files.readString(path)).find()).as("acceso a tablas de exam desde %s",path).isFalse();
		}
	}
	@ArchTest static void examOnlyUsesPublicApis(JavaClasses classes) {
		var allowed=Set.of("com.talenti.identity.CurrentUser","com.talenti.identity.Role",
				"com.talenti.application.ApplicationStages","com.talenti.vacancy.VacancyLookup","com.talenti.vacancy.VacancyInfo",
				"com.talenti.profile.ProfileWatermarkName");
		for(var c:classes) if(c.getPackageName().startsWith("com.talenti.exam"))
			for(var d:c.getDirectDependenciesFromSelf()) {
				String name=d.getTargetClass().getName();
				if(name.startsWith("com.talenti.")&&!name.startsWith("com.talenti.exam.")&&!name.startsWith("com.talenti.shared."))
					assertThat(allowed).contains(name.split("\\$")[0]);
			}
	}
	@ArchTest static void backendNeverSpawnsStudentProcesses(JavaClasses classes) {
		noClasses().that().resideInAPackage("com.talenti.exam..")
				.should().dependOnClassesThat().haveFullyQualifiedName("java.lang.ProcessBuilder").check(classes);
		noClasses().that().resideInAPackage("com.talenti.exam..")
				.should().dependOnClassesThat().haveFullyQualifiedName("java.lang.Runtime").check(classes);
	}
	@ArchTest static void domainDoesNotUseInfrastructure(JavaClasses classes) {
		noClasses().that().resideInAPackage("com.talenti.exam.domain..")
				.should().dependOnClassesThat().resideInAnyPackage("org.springframework..","java.sql..","java.net..","java.nio.file..").check(classes);
	}
	/** 014-55: las fotos nunca llegan a la IA ni a otro proveedor. */
	@ArchTest static void t014_55_snapshotsNeverReachAiPorts(JavaClasses classes) {
		noClasses().that().resideInAPackage("com.talenti.exam.snapshot..")
				.should().dependOnClassesThat().resideInAnyPackage("com.talenti.exam.port..","com.talenti.exam.adapter..",
						"com.talenti.shared.ai..","java.net..","org.springframework.web.client..")
				.check(classes);
		var ai=java.util.Set.of("com.talenti.exam.port.ExamAuthoringPort","com.talenti.exam.port.OpenAnswerGraderPort");
		for(var c:classes) {
			boolean usesAi=c.getPackageName().startsWith("com.talenti.exam.adapter")||c.getPackageName().startsWith("com.talenti.exam.port")
					||c.getPackageName().startsWith("com.talenti.shared.ai")
					||c.getDirectDependenciesFromSelf().stream().anyMatch(d->ai.contains(d.getTargetClass().getName())
							||d.getTargetClass().getPackageName().startsWith("com.talenti.shared.ai"));
			if(usesAi) assertThat(c.getDirectDependenciesFromSelf().stream().map(d->d.getTargetClass().getPackageName()))
					.as("%s usa IA y no puede ver fotos",c.getName()).noneMatch(p->p.startsWith("com.talenti.exam.snapshot"));
		}
	}
	/** 014-57: ExamProctoring y ExamResults son la interfaz pública; nadie fuera de exam usa sus clases internas. */
	@ArchTest static void t014_57_proctoringIsPublicInterface(JavaClasses classes) {
		assertThat(com.talenti.exam.ExamProctoring.class).isInterface();
		assertThat(classes.get("com.talenti.exam.snapshot.ExamSnapshotService").isAssignableTo(com.talenti.exam.ExamProctoring.class)).isTrue();
	}
	/** Solo exam pide el nombre para la marca de agua (014-43). */
	@ArchTest static void onlyExamUsesWatermarkName(JavaClasses classes) {
		noClasses().that().resideOutsideOfPackages("com.talenti.exam..","com.talenti.profile..")
				.should().dependOnClassesThat().belongToAnyOf(com.talenti.profile.ProfileWatermarkName.class).check(classes);
	}
}
