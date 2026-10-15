package com.talenti.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.talenti.matching.MatchingIntegrationTest;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

/**
 * Los registros (logs) no contienen datos personales al postular y retirar: ni el correo, ni la contraseña, ni lo que
 * el estudiante tiene en su perfil. Se sube el nivel de registro al máximo (salvo TRACE de {@code org.springframework.jdbc}).
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"talenti.demo-data.enabled=true", "talenti.test.context=application-never-logged", "logging.level.com.talenti=TRACE",
		"logging.level.org.springframework.security=TRACE", "logging.level.org.springframework.web=TRACE",
		"logging.level.org.springframework.jdbc=DEBUG", "logging.level.org.springframework.transaction=TRACE",
		"spring.mvc.log-request-details=true" })
class ApplicationNeverLoggedTests extends MatchingIntegrationTest {

	/** Las postulaciones y las vacantes de prueba no deben quedar: otras pruebas comparten la misma base. */
	@org.junit.jupiter.api.AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM application");
	}

	@Test
	@DisplayName("008-11: postular, listar y retirar no dejan en los registros el correo, la contraseña ni datos del perfil")
	void t008_11_noPersonalDataInLogs(CapturedOutput output) {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		String mark = "marca" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		ok(api.put(PROFILE + "/academic", body("career", "Carrera " + mark, "institute", "Instituto " + mark, "cycle", 4)));

		Response created = api.post("/api/v1/applications", Map.of("vacancyId", BACKEND_JAVA));
		String id = created.json().get("id").asString();
		List<Response> responses = List.of(created, api.get("/api/v1/applications"),
				api.get("/api/v1/applications/summary"), api.get("/api/v1/applications/" + id),
				api.post("/api/v1/applications", Map.of("vacancyId", BACKEND_JAVA)),
				api.post("/api/v1/applications/" + id + "/withdraw", null),
				api.post("/api/v1/applications", Map.of("vacancyId", mark)));

		assertThat(responses).extracting(Response::status).containsExactly(201, 200, 200, 200, 409, 200, 404);
		assertThat(output.getAll()).isNotEmpty().doesNotContain(mark).doesNotContain(student.email())
				.doesNotContain(student.password());
	}

}
