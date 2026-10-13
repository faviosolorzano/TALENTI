package com.talenti.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

/**
 * 006-12: los registros (logs) no contienen el contenido del perfil ni de los proyectos, ni el correo. Se sube el
 * nivel de registro al máximo en las partes que tocan estas rutas para que la prueba sea exigente, y se usan marcas
 * únicas dentro de cada campo (también en valores que se rechazan con 422). El único nivel que no se sube es TRACE de
 * {@code org.springframework.jdbc}: el propio Spring imprime ahí los valores de cada parámetro SQL, y ningún perfil de
 * Talenti lo enciende (dev usa DEBUG para com.talenti; prod, WARN e INFO).
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"logging.level.com.talenti=TRACE",
		"logging.level.org.springframework.security=TRACE",
		"logging.level.org.springframework.web=TRACE",
		"logging.level.org.springframework.jdbc=DEBUG",
		"logging.level.org.springframework.transaction=TRACE",
		"logging.level.org.springframework.orm.jpa=DEBUG",
		"spring.mvc.log-request-details=true" })
class ProfileNeverLoggedTests extends ProfileIntegrationTest {

	@Test
	@DisplayName("006-12: ni los datos académicos, ni las habilidades, ni los proyectos, ni el correo aparecen en los registros")
	void t006_12_profileContentIsNeverLogged(CapturedOutput output) {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		String mark = "marca" + UUID.randomUUID().toString().replace("-", "").substring(0, 14);

		List<Response> responses = List.of(
				api.put(PROFILE + "/academic", body("career", "Carrera " + mark, "institute", "Instituto " + mark, "cycle", 4)),
				api.put(PROFILE + "/academic", body("career", "x", "institute", mark + repeat('z', 90))),
				api.put(PROFILE + "/skills", skills(skill("java", 2))),
				api.put(PROFILE + "/skills", skills(skill(mark, 2))),
				api.put(PROFILE + "/availability", body("hoursPerWeek", 12, "acceptedModalities", List.of("REMOTE"),
						"location", "Ciudad " + mark)),
				api.post(PROFILE + "/projects", project("Proyecto " + mark, "Descripcion " + mark, "Tec" + mark.substring(0, 10))),
				api.post(PROFILE + "/projects", project("P " + mark, "corta", "Java")),
				api.get(PROFILE));

		assertThat(responses).extracting(Response::status).containsExactly(200, 422, 200, 422, 200, 201, 422, 200);
		// Los errores 422 tampoco devuelven el valor recibido.
		assertThat(responses.get(1).body()).doesNotContain(mark);
		assertThat(responses.get(3).body()).doesNotContain(mark);
		assertThat(output.getAll()).as("registros de la aplicación").isNotEmpty()
				.doesNotContain(mark)
				.doesNotContain(student.email())
				.doesNotContain(student.password());
	}

}
