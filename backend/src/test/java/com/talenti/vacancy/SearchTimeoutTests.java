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
 * Costo acotado de la búsqueda: el JIT de PostgreSQL se apaga y cada consulta tiene un tiempo máximo (propiedad
 * {@code talenti.vacancy.search-timeout}); al agotarse responde 503 {@code search-timeout} sin el texto buscado.
 * Aquí el tiempo máximo es de 1 ms y hay miles de vacantes de relleno, así que la búsqueda siempre se agota.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"talenti.demo-data.enabled=true",
		"talenti.vacancy.search-timeout=1ms",
		"logging.level.com.talenti=TRACE",
		"logging.level.org.springframework.transaction=DEBUG",
		"logging.level.org.springframework.jdbc=DEBUG" })
class SearchTimeoutTests extends VacancyIntegrationTest {

	private void insertFiller() {
		jdbc.update("""
				INSERT INTO vacancy (company_id, title, summary, description, modality, location, hours_per_week,
				                     published_at, status)
				SELECT ?::uuid, 'Relleno ' || n, 'Resumen de relleno ' || n || ' con varias palabras para recorrer.',
				       repeat('Texto largo de relleno para la descripcion. ', 20), 'ONSITE', 'Lima', 10,
				       now() - (n || ' minutes')::interval, 'OPEN'
				FROM generate_series(1, 3000) AS n""", NEXA_DIGITAL);
	}

	private void deleteFiller() {
		jdbc.update("DELETE FROM vacancy WHERE title LIKE 'Relleno %'");
	}

	@Test
	@DisplayName("004-14: una búsqueda que supera el tiempo máximo responde 503 search-timeout, sin caché y sin el texto buscado en la respuesta ni en los registros")
	void t004_14_timeoutIsA503WithoutTheSearchText(CapturedOutput output) {
		String secret = "zztiempo" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		insertFiller();
		try {
			Response response = client().get("/api/v1/vacancies?q=" + secret + "%20programacion%20relleno&size=24");

			assertThat(response.status()).isEqualTo(503);
			assertThat(response.header("Content-Type")).startsWith("application/problem+json");
			assertThat(response.json().get("code").asString()).isEqualTo("search-timeout");
			assertThat(response.json().get("status").asInt()).isEqualTo(503);
			assertThat(response.json().get("detail").asString()).isNotBlank();
			assertThat(response.setCookies()).isEmpty();
			assertThat(response.header("Cache-Control")).as("un error no se guarda en caché").contains("no-store");
			assertThat(response.header("ETag")).isNull();
			assertThat(response.body()).doesNotContain(secret);
			assertThat(output.getAll()).as("registros de la aplicación").contains("tiempo máximo").doesNotContain(secret);
		}
		finally {
			deleteFiller();
		}
	}

	@Test
	@DisplayName("004-14: tras un tiempo agotado la conexión vuelve limpia y el resto de la API sigue respondiendo")
	void t004_14_afterATimeoutEverythingElseStillWorks() {
		insertFiller();
		try {
			assertThat(client().get("/api/v1/vacancies?q=programacion%20relleno").status()).isEqualTo(503);
			assertThat(client().get("/api/v1/vacancies?q=programacion%20relleno").status()).isEqualTo(503);

			assertThat(client().get("/api/v1/vacancies/filters").status()).isEqualTo(200);
			assertThat(client().get("/api/v1/vacancies/featured").status()).isEqualTo(200);
			assertThat(jdbc.queryForObject("SHOW statement_timeout", String.class)).as("SET LOCAL no se filtra").isEqualTo("0");
			assertThat(jdbc.queryForObject("SHOW jit", String.class)).isEqualTo("on");
		}
		finally {
			deleteFiller();
		}
	}

}
