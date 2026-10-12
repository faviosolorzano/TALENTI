package com.talenti.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.talenti.support.ApiClient.Response;

/**
 * 004-14: el texto de {@code q} no aparece en los registros (logs) ni se guarda. Se sube el nivel de la aplicación
 * a TRACE (más que el DEBUG del perfil dev) y el de JDBC y transacciones a DEBUG, que escriben la consulta pero no
 * sus valores.
 *
 * <p>Fuera de esta prueba queda a propósito el nivel DEBUG/TRACE de {@code org.springframework.security} y
 * {@code org.springframework.web} (FilterChainProxy, DispatcherServlet, HandlerMethod): esos registros del marco
 * imprimen la URL con su cadena de consulta y los argumentos del método. Ni dev ni prod los activan; quien los
 * encienda para depurar verá el texto buscado (y, en nginx, el registro de accesos también lo trae).
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"talenti.demo-data.enabled=true",
		"logging.level.com.talenti=TRACE",
		"logging.level.org.springframework.transaction=DEBUG",
		"logging.level.org.springframework.jdbc=DEBUG" })
class SearchTextNeverLoggedTests extends VacancyIntegrationTest {

	@Test
	@DisplayName("004-14: el texto buscado no aparece en los registros (búsqueda válida, sin resultados, inválida) ni se guarda")
	void t004_14_searchTextIsNeverLoggedNorStored(CapturedOutput output) {
		String secret = "zzsecreto" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		String tooLong = secret + "x".repeat(90);
		int auditBefore = count("SELECT count(*) FROM audit_event");

		Response found = client().get("/api/v1/vacancies?q=backend%20" + secret + "&modality=REMOTE");
		Response none = client().get("/api/v1/vacancies?q=" + secret);
		Response invalid = client().get("/api/v1/vacancies?q=" + tooLong);
		Response badSort = client().get("/api/v1/vacancies?q=" + secret + "&sort=" + secret);
		Response detail = client().get("/api/v1/vacancies/no-existe");

		assertThat(found.status()).isEqualTo(200);
		assertThat(none.status()).isEqualTo(200);
		assertThat(invalid.status()).isEqualTo(422);
		assertThat(badSort.status()).isEqualTo(422);
		assertThat(detail.status()).isEqualTo(404);
		// Hay registros (la prueba es exigente) y ninguno contiene el texto buscado.
		assertThat(output.getAll()).as("registros de la aplicación").isNotEmpty().doesNotContain(secret);
		// Y no se guarda: ni en la auditoría ni en ninguna columna de texto de las tablas de vacantes.
		assertThat(count("SELECT count(*) FROM audit_event")).isEqualTo(auditBefore);
		assertThat(count("SELECT count(*) FROM audit_event WHERE details::text LIKE ?", "%" + secret + "%")).isZero();
		assertThat(count("SELECT count(*) FROM vacancy WHERE title LIKE ? OR summary LIKE ? OR description LIKE ?",
				"%" + secret + "%", "%" + secret + "%", "%" + secret + "%")).isZero();
		// Las respuestas de error tampoco lo repiten.
		assertThat(invalid.body()).doesNotContain(secret);
		assertThat(badSort.body()).doesNotContain(secret);
	}

	@Test
	@DisplayName("004-14: el texto buscado tampoco llega a los registros cuando la consulta falla por un error de la base de datos")
	void t004_14_searchTextIsNeverLoggedWhenTheQueryFails(CapturedOutput output) {
		String secret = "zzfallo" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		// Un error real de PostgreSQL en la consulta de búsqueda: la columna que lee ya no existe.
		jdbc.execute("ALTER TABLE vacancy RENAME COLUMN summary TO summary_renamed");
		Response failed;
		try {
			failed = client().get("/api/v1/vacancies?q=" + secret + "%20java");
		}
		finally {
			jdbc.execute("ALTER TABLE vacancy RENAME COLUMN summary_renamed TO summary");
		}

		assertThat(failed.status()).isEqualTo(500);
		assertThat(failed.json().get("code").asString()).isEqualTo("internal-error");
		assertThat(failed.body()).doesNotContain(secret);
		assertThat(output.getAll()).as("registros de la aplicación").contains("Error no controlado")
				.doesNotContain(secret);
		assertThat(client().get("/api/v1/vacancies?q=java").status()).as("la búsqueda se recupera").isEqualTo(200);
	}

}
