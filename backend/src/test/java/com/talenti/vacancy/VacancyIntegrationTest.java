package com.talenti.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.talenti.TestcontainersConfiguration;
import com.talenti.support.ApiClient;

import tools.jackson.databind.JsonNode;

/**
 * Base de las pruebas de la API de vacantes (spec 004): aplicación completa con servidor HTTP real, PostgreSQL real
 * (Testcontainers, rol de la aplicación sin superusuario) y los datos de ejemplo cargados
 * ({@code talenti.demo-data.enabled=true}): 8 empresas y 12 vacantes abiertas con identificadores fijos.
 *
 * <p>Las pruebas que agregan filas (una vacante cerrada, una con caracteres especiales...) las borran al terminar,
 * así que los totales de la semilla (12 abiertas) se mantienen entre pruebas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "talenti.demo-data.enabled=true")
@Import(TestcontainersConfiguration.class)
public abstract class VacancyIntegrationTest {

	protected static final String NEXA_DIGITAL = "c0000000-0000-4000-8000-000000000001";

	protected static final String ANDES_TECH = "c0000000-0000-4000-8000-000000000002";

	/** "Practicante Backend Java": los requisitos del ejemplo de RN-01. */
	protected static final String BACKEND_JAVA = "d0000000-0000-4000-8000-000000000001";

	/** Los títulos de las 12 vacantes de la semilla, de la más reciente a la más antigua. */
	protected static final List<String> SEED_TITLES_RECENT_FIRST = List.of(
			"Practicante DevOps junior",
			"Practicante React y TypeScript",
			"Practicante Backend Java",
			"Practicante Frontend",
			"Practicante de Calidad",
			"Practicante de Datos con SQL",
			"Practicante iOS",
			"Practicante Python y automatización",
			"Practicante Android",
			"Practicante Full Stack Node.js",
			"Practicante .NET",
			"Practicante PHP y Laravel");

	@LocalServerPort
	protected int port;

	@Autowired
	protected JdbcTemplate jdbc;

	protected ApiClient client() {
		return new ApiClient(port);
	}

	/** GET al listado con la cadena de consulta tal cual (por ejemplo {@code ?q=java&size=5}); espera 200. */
	protected JsonNode list(String query) {
		ApiClient.Response response = client().get("/api/v1/vacancies" + query);
		assertThat(response.status()).as("GET /vacancies" + query).isEqualTo(200);
		return response.json();
	}

	protected static List<String> titles(JsonNode page) {
		List<String> titles = new ArrayList<>();
		for (JsonNode item : page.get("items")) {
			titles.add(item.get("title").asString());
		}
		return titles;
	}

	protected static List<String> ids(JsonNode page) {
		List<String> ids = new ArrayList<>();
		for (JsonNode item : page.get("items")) {
			ids.add(item.get("id").asString());
		}
		return ids;
	}

	/** Inserta una vacante abierta de Nexa Digital (presencial en Lima, salvo que se cambie) y devuelve su id. */
	protected UUID insertVacancy(String title, String summary, OffsetDateTime publishedAt, String status,
			boolean featured) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO vacancy (id, company_id, title, summary, description, modality, location, hours_per_week,
				                     published_at, status, featured)
				VALUES (?, ?::uuid, ?, ?, 'Vacante de prueba.', 'ONSITE', 'Lima', 10, ?, ?, ?)""",
				id, NEXA_DIGITAL, title, summary, publishedAt, status, featured);
		return id;
	}

	protected UUID insertOpenVacancy(String title) {
		return insertVacancy(title, "Resumen de prueba.", OffsetDateTime.now(ZoneOffset.UTC).minusHours(1), "OPEN",
				false);
	}

	protected void deleteVacancy(UUID id) {
		jdbc.update("DELETE FROM vacancy WHERE id = ?", id);
	}

	protected int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

}
