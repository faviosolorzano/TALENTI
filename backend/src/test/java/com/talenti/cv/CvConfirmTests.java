package com.talenti.cv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.support.ApiClient.Response;
import com.talenti.support.SampleCv;

import tools.jackson.databind.JsonNode;

/** Revisar y confirmar sugerencias (spec 009): 009-13 a 009-16. Nada entra al perfil sin confirmar. */
class CvConfirmTests extends CvIntegrationTest {

	private String confirmPath(JsonNode analysis) {
		return CV + "/analyses/" + analysis.get("id").asString() + "/confirm";
	}

	// ------------------------------------------------------------------------------------------ 009-14

	@Test
	@DisplayName("009-14: las sugerencias traen id, tipo, campo, valor, frase de respaldo y valor actual; el perfil no cambia")
	void t009_14_suggestionsDoNotChangeTheProfile() {
		RegisteredStudent student = registerStudent();
		JsonNode before = student.api().get(PROFILE).json();

		JsonNode analysis = uploadSampleAndWait(student.api());

		assertThat(student.api().get(PROFILE).json()).isEqualTo(before);
		JsonNode java = skillSuggestion(analysis, "java");
		assertThat(java.get("id").asString()).isNotBlank();
		assertThat(java.get("value").get("name").asString()).isEqualTo("Java");
		assertThat(java.get("value").get("level").isNull()).as("el CV no dice el nivel").isTrue();
		assertThat(java.get("evidence").asString()).contains("Java");
		assertThat(java.get("currentValue").isNull()).isTrue();
		assertThat(academicSuggestion(analysis, "CAREER").get("value").get("value").asString())
				.isEqualTo("Diseño y Desarrollo de Software");
		assertThat(academicSuggestion(analysis, "INSTITUTE").get("value").get("value").asString())
				.isEqualTo("Instituto Tecsup");
		assertThat(academicSuggestion(analysis, "CYCLE").get("value").get("value").asInt()).isEqualTo(5);
		assertThat(suggestions(analysis, "PROJECT")).hasSize(2);
		assertThat(suggestions(analysis, "PROJECT").get(0).get("value").get("technologies")).isNotEmpty();
		assertThat(analysis.toString()).doesNotContain(SampleCv.EMAIL).doesNotContain("987 654").doesNotContain("Quispe");
	}

	@Test
	@DisplayName("009-14: lo que el perfil ya tiene igual o mejor no se vuelve a sugerir y se cuenta")
	void t009_14_alreadyInProfileIsNotSuggested() {
		RegisteredStudent student = registerStudent();
		ok(student.api().put(PROFILE + "/academic", body("career", "Diseño y Desarrollo de Software", "institute",
				"Otro Instituto", "cycle", 5)));
		ok(student.api().put(PROFILE + "/skills", skills(skill("java", 3), skill("sql", 1))));
		ok(student.api().post(PROFILE + "/projects", project("Tienda Web", description(), "Java")));

		JsonNode analysis = uploadSampleAndWait(student.api());

		assertThat(suggestions(analysis, "SKILL")).extracting(s -> s.get("value").get("slug").asString())
				.doesNotContain("java", "sql").contains("docker");
		assertThat(suggestions(analysis, "ACADEMIC")).extracting(s -> s.get("field").asString())
				.containsExactly("INSTITUTE");
		assertThat(academicSuggestion(analysis, "INSTITUTE").get("currentValue").asString()).isEqualTo("Otro Instituto");
		assertThat(suggestions(analysis, "PROJECT")).extracting(s -> s.get("value").get("name").asString())
				.containsExactly("Sistema de Notas");
		// carrera, ciclo, java, sql y Tienda Web
		assertThat(analysis.get("alreadyInProfile").asInt()).isEqualTo(5);
	}

	// ------------------------------------------------------------------------------------------ 009-15

	@Test
	@DisplayName("009-15: acepta, edita y descarta; lo aceptado entra al perfil como declarado y el porcentaje se recalcula")
	void t009_15_confirmAppliesAcceptedAndEdited() {
		RegisteredStudent student = registerStudent();
		JsonNode analysis = uploadSampleAndWait(student.api());
		String firstProject = suggestions(analysis, "PROJECT").get(0).get("id").asString();

		Response response = student.api().post(confirmPath(analysis), confirmBody(List.of(
				decision(skillSuggestion(analysis, "java"), "ACCEPT", Map.of("level", 2)),
				decision(skillSuggestion(analysis, "docker"), "ACCEPT", Map.of("level", 1)),
				decision(skillSuggestion(analysis, "sql"), "EDIT", Map.of("level", 3)),
				decision(skillSuggestion(analysis, "react"), "DISCARD", null),
				decision(academicSuggestion(analysis, "CAREER"), "ACCEPT", null),
				decision(academicSuggestion(analysis, "INSTITUTE"), "EDIT", Map.of("value", "Instituto Demo")),
				body("suggestionId", firstProject, "action", "EDIT", "value", Map.of("description",
						"Una tienda en línea hecha con Java y mucho cariño.")))));

		assertThat(response.status()).as(response.body()).isEqualTo(200);
		JsonNode profile = response.json();
		assertThat(profile.get("skills")).extracting(s -> s.get("slug").asString() + ":" + s.get("level").asInt() + ":"
				+ s.get("evidence").asString()).containsExactlyInAnyOrder("java:2:DECLARED", "docker:1:DECLARED",
						"sql:3:DECLARED");
		assertThat(profile.get("academic").get("career").asString()).isEqualTo("Diseño y Desarrollo de Software");
		assertThat(profile.get("academic").get("institute").asString()).as("lo editado manda").isEqualTo("Instituto Demo");
		assertThat(profile.get("academic").get("cycle").isNull()).as("lo omitido se descarta").isTrue();
		assertThat(profile.get("projects")).hasSize(1);
		assertThat(profile.get("projects").get(0).get("description").asString()).contains("mucho cariño");
		assertThat(profile.get("completeness").get("percent").asInt()).isEqualTo(
				student.api().get(PROFILE).json().get("completeness").get("percent").asInt()).isPositive();
		// El análisis se cerró y lo rechazado no se guardó en ningún lado.
		assertThat(student.api().get(CV + "/analyses/current").status()).isEqualTo(204);
		assertThat(jdbc.queryForObject("SELECT status FROM cv_analysis WHERE id = ?::uuid", String.class,
				analysis.get("id").asString())).isEqualTo("CONFIRMED");
		assertThat(jdbc.queryForObject("SELECT reduced_text FROM cv_analysis WHERE id = ?::uuid", String.class,
				analysis.get("id").asString())).isNull();
		assertThat(count("SELECT count(*) FROM student_skill WHERE user_id = ?", student.id())).isEqualTo(3);
	}

	@Test
	@DisplayName("009-15: la auditoría de la confirmación solo lleva conteos")
	void t009_15_auditHasOnlyCounts() {
		RegisteredStudent student = registerStudent();
		JsonNode analysis = uploadSampleAndWait(student.api());

		student.api().post(confirmPath(analysis), confirmBody(List.of(
				decision(skillSuggestion(analysis, "java"), "ACCEPT", Map.of("level", 2)),
				decision(skillSuggestion(analysis, "sql"), "DISCARD", null))));

		Map<String, Object> event = jdbc.queryForMap("SELECT details::text AS details, actor_user_id FROM audit_event "
				+ "WHERE event_type = 'CV_ANALYSIS_CONFIRMED' AND subject_id = ?::uuid", analysis.get("id").asString());
		assertThat(event.get("actor_user_id")).isEqualTo(student.id());
		JsonNode details = JSON.readTree((String) event.get("details"));
		assertThat(details.get("accepted").asInt()).isEqualTo(1);
		assertThat(details.get("edited").asInt()).isZero();
		assertThat(details.get("discarded").asInt()).isEqualTo(analysis.get("suggestions").size() - 1);
		assertThat(details.toString()).doesNotContain("Java").doesNotContain("java");
	}

	@Test
	@DisplayName("009-15: una habilidad aceptada exige nivel; el error trae la ruta del campo y no se aplica nada")
	void t009_15_skillNeedsALevelAndNothingIsApplied() {
		RegisteredStudent student = registerStudent();
		JsonNode analysis = uploadSampleAndWait(student.api());

		Response response = student.api().post(confirmPath(analysis), confirmBody(List.of(
				decision(academicSuggestion(analysis, "CAREER"), "ACCEPT", null),
				decision(skillSuggestion(analysis, "java"), "ACCEPT", null),
				decision(skillSuggestion(analysis, "sql"), "ACCEPT", Map.of("level", 9)))));

		assertThat(response.status()).isEqualTo(422);
		List<String> fields = new ArrayList<>();
		for (JsonNode error : response.json().get("errors")) {
			fields.add(error.get("field").asString() + ":" + error.get("rule").asString());
		}
		assertThat(fields).containsExactlyInAnyOrder("decisions[1].value.level:invalid-level",
				"decisions[2].value.level:invalid-level");
		// Atomicidad: lo válido de la misma solicitud tampoco se aplicó, y el análisis sigue abierto.
		assertThat(student.api().get(PROFILE).json().get("academic").get("career").isNull()).isTrue();
		assertThat(student.api().get(CV + "/analyses/current").json().get("status").asString()).isEqualTo("READY");
	}

	@Test
	@DisplayName("009-15: un id repetido, ajeno o una acción inválida dan 422 con su ruta")
	void t009_15_badDecisions() {
		RegisteredStudent student = registerStudent();
		RegisteredStudent other = registerStudent();
		JsonNode analysis = uploadSampleAndWait(student.api());
		JsonNode otherAnalysis = uploadSampleAndWait(other.api());
		JsonNode java = skillSuggestion(analysis, "java");

		Response response = student.api().post(confirmPath(analysis), confirmBody(List.of(
				decision(java, "ACCEPT", Map.of("level", 2)),
				decision(java, "ACCEPT", Map.of("level", 2)),
				decision(skillSuggestion(otherAnalysis, "sql"), "ACCEPT", Map.of("level", 1)),
				body("suggestionId", "no-es-un-id", "action", "ACCEPT"),
				decision(skillSuggestion(analysis, "docker"), "COMPRAR", null),
				decision(skillSuggestion(analysis, "react"), "EDIT", null))));

		assertThat(response.status()).isEqualTo(422);
		List<String> fields = new ArrayList<>();
		for (JsonNode error : response.json().get("errors")) {
			fields.add(error.get("field").asString() + ":" + error.get("rule").asString());
		}
		assertThat(fields).containsExactlyInAnyOrder("decisions[1].suggestionId:duplicate",
				"decisions[2].suggestionId:unknown-suggestion", "decisions[3].suggestionId:unknown-suggestion",
				"decisions[4].action:invalid-action", "decisions[5].value:required");
		assertThat(student.api().get(PROFILE).json().get("skills")).isEmpty();
	}

	@Test
	@DisplayName("009-15: una habilidad sin asociar exige elegir una del catálogo; con una válida se acepta")
	void t009_15_unassociatedSkill() {
		RegisteredStudent student = registerStudent();
		JsonNode analysis = uploadSampleAndWait(student.api());
		UUID id = UUID.fromString(analysis.get("id").asString());
		UUID suggestion = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO cv_suggestion (id, analysis_id, kind, field, payload, evidence, position)
				VALUES (?, ?, 'SKILL', NULL, '{"slug":null,"name":"Cobol","level":null}'::jsonb, 'Cobol', 99)""",
				suggestion, id);
		JsonNode view = student.api().get(CV + "/analyses/current").json();
		JsonNode cobol = suggestions(view, "SKILL").stream().filter(s -> s.get("value").get("slug").isNull())
				.findFirst().orElseThrow();
		assertThat(cobol.get("value").get("name").asString()).isEqualTo("Cobol");

		Response noSlug = student.api().post(confirmPath(view),
				confirmBody(List.of(decision(cobol, "ACCEPT", Map.of("level", 2)))));
		assertThat(noSlug.status()).isEqualTo(422);
		assertThat(noSlug.json().get("errors").get(0).get("field").asString()).isEqualTo("decisions[0].value.slug");

		Response associated = student.api().post(confirmPath(view),
				confirmBody(List.of(decision(cobol, "ACCEPT", Map.of("level", 2, "slug", "git")))));
		assertThat(associated.status()).as(associated.body()).isEqualTo(200);
		assertThat(associated.json().get("skills")).extracting(s -> s.get("slug").asString()).containsExactly("git");
	}

	@Test
	@DisplayName("009-15: un proyecto aceptado exige al menos una tecnología y respeta el máximo de 10 (422 limit)")
	void t009_15_projectRules() {
		RegisteredStudent student = registerStudent();
		JsonNode analysis = uploadSampleAndWait(student.api());
		JsonNode project = suggestions(analysis, "PROJECT").get(0);

		Response noTechnology = student.api().post(confirmPath(analysis), confirmBody(List.of(
				decision(project, "EDIT", Map.of("technologies", List.of())))));
		assertThat(noTechnology.status()).isEqualTo(422);
		assertThat(noTechnology.json().get("errors").get(0).get("field").asString())
				.isEqualTo("decisions[0].value.technologies");

		for (int i = 0; i < 10; i++) {
			ok(student.api().post(PROFILE + "/projects", project("Proyecto " + i, description(), "Java")));
		}
		Response overLimit = student.api().post(confirmPath(analysis), confirmBody(List.of(
				decision(project, "ACCEPT", null))));
		assertThat(overLimit.status()).isEqualTo(422);
		assertThat(overLimit.json().get("errors").get(0).get("rule").asString()).isEqualTo("limit");
		assertThat(student.api().get(PROFILE).json().get("projects")).hasSize(10);
	}

	@Test
	@DisplayName("009-15: confirmar dos veces, o dos pestañas a la vez, aplica una sola y la otra recibe 404")
	void t009_15_doubleConfirm() throws Exception {
		RegisteredStudent student = registerStudent();
		JsonNode analysis = uploadSampleAndWait(student.api());
		Map<String, Object> request = confirmBody(List.of(
				decision(skillSuggestion(analysis, "java"), "ACCEPT", Map.of("level", 2))));

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Callable<Response>> tasks = List.of(() -> student.api().post(confirmPath(analysis), request),
					() -> student.api().post(confirmPath(analysis), request));
			List<Integer> statuses = new ArrayList<>();
			for (Future<Response> future : pool.invokeAll(tasks)) {
				statuses.add(future.get().status());
			}
			assertThat(statuses).containsExactlyInAnyOrder(200, 404);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(student.api().post(confirmPath(analysis), request).status()).isEqualTo(404);
		assertThat(count("SELECT count(*) FROM student_skill WHERE user_id = ?", student.id())).isEqualTo(1);
	}

	@Test
	@DisplayName("009-15: confirmar sin aceptar nada cierra el análisis y deja el perfil como estaba")
	void t009_15_confirmWithNothingAccepted() {
		RegisteredStudent student = registerStudent();
		JsonNode analysis = uploadSampleAndWait(student.api());

		Response response = student.api().post(confirmPath(analysis), confirmBody(List.of()));

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.json().get("skills")).isEmpty();
		assertThat(profileRows(student.id())).as("no crea ni el perfil").isZero();
		assertThat(student.api().get(CV + "/analyses/current").status()).isEqualTo(204);
	}

	// ------------------------------------------------------------------------------------------ 009-16

	@Test
	@DisplayName("009-16: descartar borra el análisis, deja el perfil como estaba y audita CV_ANALYSIS_DISCARDED")
	void t009_16_discard() {
		RegisteredStudent student = registerStudent();
		ok(student.api().put(PROFILE + "/academic", body("career", "Redes", "institute", "Demo", "cycle", 2)));
		JsonNode before = student.api().get(PROFILE).json();
		JsonNode analysis = uploadSampleAndWait(student.api());
		String path = CV + "/analyses/" + analysis.get("id").asString();

		assertThat(student.api().delete(path).status()).isEqualTo(204);

		assertThat(student.api().get(PROFILE).json()).isEqualTo(before);
		assertThat(student.api().delete(path).status()).as("ya cerrado").isEqualTo(404);
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'CV_ANALYSIS_DISCARDED' AND subject_id = ?::uuid",
				analysis.get("id").asString())).isEqualTo(1);
		assertThat(student.api().post(path + "/confirm", confirmBody(List.of())).status()).isEqualTo(404);
	}

	@Test
	@DisplayName("009-16: se puede descartar un análisis que todavía se está leyendo y su resultado nunca aparece")
	void t009_16_discardWhileReading() {
		RegisteredStudent student = registerStudent();
		Response uploaded = upload(student.api(), SampleCv.pdf("MARCA"));
		String id = uploaded.json().get("id").asString();

		assertThat(student.api().delete(CV + "/analyses/" + id).status()).isEqualTo(204);

		try {
			Thread.sleep(400);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		assertThat(student.api().get(CV + "/analyses/current").status()).isEqualTo(204);
		assertThat(count("SELECT count(*) FROM cv_suggestion WHERE analysis_id = ?::uuid", id)).isZero();
		assertThat(jdbc.queryForObject("SELECT status FROM cv_analysis WHERE id = ?::uuid", String.class, id))
				.isEqualTo("DISCARDED");
	}

	private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
			.build();

}
