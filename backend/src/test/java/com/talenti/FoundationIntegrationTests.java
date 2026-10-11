package com.talenti;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Pruebas de la Fase 0 (T-01): el sistema arranca contra PostgreSQL 18 con pgvector real,
 * Flyway aplica la migración V1, la salud responde y todo lo demás exige sesión.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class FoundationIntegrationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	@DisplayName("T01-01: el contexto arranca contra PostgreSQL con pgvector y Hibernate valida el esquema")
	void T01_01_contextLoadsAgainstPgvector() {
		String version = jdbcTemplate.queryForObject("SELECT current_setting('server_version_num')", String.class);

		assertThat(version).startsWith("18");
	}

	@Test
	@DisplayName("T01-02: la migración V1 se aplica y deja la extensión vector habilitada")
	void T01_02_migrationV1EnablesVectorExtension() {
		Boolean applied = jdbcTemplate.queryForObject(
				"SELECT success FROM flyway_schema_history WHERE version = '1'", Boolean.class);
		Integer extensions = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);

		assertThat(applied).isTrue();
		assertThat(extensions).isEqualTo(1);
	}

	@Test
	@DisplayName("T01-03: pgvector funciona de verdad (distancia entre dos vectores)")
	void T01_03_vectorDistanceWorks() {
		Double distance = jdbcTemplate.queryForObject("SELECT '[1,2,3]'::vector <-> '[1,2,3]'::vector", Double.class);

		assertThat(distance).isZero();
	}

	@Test
	@DisplayName("T01-04: GET /actuator/health es público y responde 200 con estado UP")
	void T01_04_healthIsPublic() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("UP"));
	}

	@Test
	@DisplayName("T01-05: la salud no revela detalles ni componentes")
	void T01_05_healthDoesNotLeakDetails() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(jsonPath("$.components").doesNotExist())
				.andExpect(jsonPath("$.details").doesNotExist());
	}

	@Test
	@DisplayName("T01-06: una ruta cualquiera sin sesión responde 401 en formato Problem Details")
	void T01_06_anyRouteWithoutSessionIs401() throws Exception {
		mockMvc.perform(get("/api/v1/cualquier-cosa"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.title").value("No autenticado"));
	}

	@Test
	@DisplayName("T01-07: otros endpoints de actuator sin sesión responden 401")
	void T01_07_otherActuatorEndpointsRequireSession() throws Exception {
		mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
	}

	@Test
	@WithMockUser
	@DisplayName("T01-08: actuator expone solo health (con sesión, env e info no existen)")
	void T01_08_actuatorExposesOnlyHealth() throws Exception {
		mockMvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
		mockMvc.perform(get("/actuator/info")).andExpect(status().isNotFound());
	}

}
