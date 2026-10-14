package com.talenti.cv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;
import com.talenti.support.SampleCv;
import com.talenti.support.TestPdfs;

import tools.jackson.databind.JsonNode;

/**
 * 009-18 (RNF-19, T6): ni los registros, ni la auditoría, ni los errores en Problem Details, ni los {@code toString}
 * contienen texto del CV, frases de evidencia, nombres ni datos del contenido. Se procesa un CV con una marca única y se
 * comprueba que no aparece en nada de lo capturado. Se sube el nivel de registro al máximo para que la prueba sea
 * exigente (salvo TRACE de {@code org.springframework.jdbc}, donde Spring imprime los valores de cada parámetro SQL y
 * ningún perfil de Talenti lo enciende).
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"logging.level.com.talenti=TRACE",
		"logging.level.org.springframework.security=TRACE",
		"logging.level.org.springframework.web=TRACE",
		"logging.level.org.springframework.jdbc=DEBUG",
		"logging.level.org.springframework.transaction=TRACE",
		"logging.level.org.apache.pdfbox=TRACE",
		"spring.mvc.log-request-details=true" })
class CvNeverLoggedTests extends CvIntegrationTest {

	@Test
	@DisplayName("009-18: la marca única, el nombre, el correo, el teléfono y el DNI de un CV nunca aparecen en los registros, la auditoría ni los errores")
	void t009_18_cvContentIsNeverLogged(CapturedOutput output) {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		String mark = SampleCv.uniqueMark();

		Response uploaded = upload(api, SampleCv.pdf(mark));
		JsonNode analysis = awaitDone(api);
		JsonNode java = skillSuggestion(analysis, "java");
		List<Response> responses = List.of(uploaded,
				// errores: un PDF falso, uno escaneado y una confirmación inválida con la marca en el valor
				upload(api, ("falso " + mark).getBytes()),
				api.post(CV + "/analyses/" + analysis.get("id").asString() + "/confirm",
						confirmBody(List.of(decision(java, "EDIT", Map.of("level", 77, "slug", mark))))),
				api.post(CV + "/analyses/" + analysis.get("id").asString() + "/confirm",
						confirmBody(List.of(body("suggestionId", mark, "action", mark)))),
				api.post(CV + "/analyses/" + analysis.get("id").asString() + "/reads", Map.of("mode", mark)),
				api.get(CV + "/usage"));
		assertThat(responses).extracting(Response::status).containsExactly(202, 422, 422, 422, 422, 200);
		api.delete(CV + "/analyses/" + analysis.get("id").asString());
		// Un PDF cifrado y otro escaneado con el nombre del archivo: tampoco dejan rastro.
		assertThat(upload(api, TestPdfs.encrypted(SampleCv.lines(mark))).status()).isEqualTo(422);
		assertThat(upload(api, TestPdfs.scanned()).status()).isEqualTo(422);

		String errors = responses.stream().map(Response::body).reduce("", String::concat);
		assertThat(errors).doesNotContain(mark).doesNotContain("Quispe").doesNotContain(SampleCv.EMAIL);
		String audit = jdbc.queryForList("SELECT details::text FROM audit_event WHERE actor_user_id = ?", String.class,
				student.id()).toString();
		assertThat(audit).isNotBlank().doesNotContain(mark).doesNotContain("Quispe").doesNotContain(SampleCv.EMAIL)
				.doesNotContain("Java").doesNotContain("mi-cv-secreto");
		assertThat(jdbc.queryForList("SELECT event_type FROM audit_event WHERE actor_user_id = ?", String.class,
				student.id())).contains("CV_UPLOADED", "CV_ANALYSIS_DISCARDED");
		assertThat(output.getAll()).as("registros de la aplicación").isNotEmpty()
				.doesNotContain(mark)
				.doesNotContain("Quispe")
				.doesNotContain("Valeria")
				.doesNotContain(SampleCv.EMAIL)
				.doesNotContain(SampleCv.DNI)
				.doesNotContain("987 654")
				.doesNotContain("Los Pinos")
				.doesNotContain("mi-cv-secreto")
				.doesNotContain(student.email())
				.doesNotContain(student.password());
	}

	@Test
	@DisplayName("009-18: el toString de los objetos del módulo no imprime contenido del CV")
	void t009_18_toStringHasNoContent() {
		String mark = "MARCATOSTRING";
		var raw = com.talenti.cv.RawSuggestion.project("Proyecto " + mark, "Descripción " + mark, List.of("Java"),
				"Evidencia " + mark);
		var draft = new com.talenti.cv.domain.SuggestionDraft(SuggestionKind.PROJECT, null,
				Map.of("name", mark), "Evidencia " + mark);
		var view = new com.talenti.cv.service.AnalysisView.SuggestionView(java.util.UUID.randomUUID(), "PROJECT", null,
				null, "Evidencia " + mark, mark);
		var decision = new com.talenti.cv.service.DecisionInput("x", "ACCEPT", null);
		var result = new com.talenti.cv.service.ReadResult(List.of(draft), false,
				com.talenti.shared.ai.AiLevel.BASIC, null, null, false);
		var extracted = new com.talenti.cv.pdf.PdfTextExtractor.Extracted("Texto " + mark, 1);
		var limited = com.talenti.cv.domain.TextLimiter.limit("Texto " + mark, 100);

		for (Object object : List.of(raw, draft, view, decision, result, extracted, limited)) {
			assertThat(object.toString()).as(object.getClass().getSimpleName()).doesNotContain(mark)
					.doesNotContain("Java");
		}
	}

}
