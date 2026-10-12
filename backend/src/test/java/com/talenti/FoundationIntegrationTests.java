package com.talenti;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

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
import org.springframework.test.web.servlet.MvcResult;

/**
 * Pruebas de la Fase 0 (T-01): el sistema arranca contra PostgreSQL 18 con pgvector real,
 * Flyway aplica la migración V1 con el rol de la aplicación (sin superusuario), la salud responde
 * y todo lo demás exige sesión.
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
	@DisplayName("T01-01: el contexto arranca contra PostgreSQL 18 (Hibernate valida el esquema)")
	void T01_01_serverIsPostgres18() {
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

	@Test
	@DisplayName("T01-09: la aplicación se conecta con un rol que NO es superusuario")
	void T01_09_applicationRoleIsNotSuperuser() {
		Map<String, Object> role = jdbcTemplate.queryForMap("""
				SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, rolreplication, rolbypassrls
				FROM pg_roles WHERE rolname = current_user""");

		assertThat(role.get("rolname")).isEqualTo(TestcontainersConfiguration.APP_USER);
		assertThat(role.get("rolsuper")).isEqualTo(false);
		assertThat(role.get("rolcreaterole")).isEqualTo(false);
		assertThat(role.get("rolcreatedb")).isEqualTo(false);
		assertThat(role.get("rolreplication")).isEqualTo(false);
		assertThat(role.get("rolbypassrls")).isEqualTo(false);
	}

	@Test
	@DisplayName("T01-10: el rol de la aplicación es dueño del esquema public y de las tablas de Flyway")
	void T01_10_applicationRoleOwnsTheSchema() {
		String schemaOwner = jdbcTemplate.queryForObject(
				"SELECT schema_owner FROM information_schema.schemata WHERE schema_name = 'public'", String.class);
		String historyOwner = jdbcTemplate.queryForObject(
				"SELECT tableowner FROM pg_tables WHERE tablename = 'flyway_schema_history'", String.class);

		assertThat(schemaOwner).isEqualTo(TestcontainersConfiguration.APP_USER);
		assertThat(historyOwner).isEqualTo(TestcontainersConfiguration.APP_USER);
	}

	@Test
	@DisplayName("T01-11: una petición anónima no crea sesión ni devuelve Set-Cookie en el 401")
	void T01_11_anonymousRequestDoesNotCreateSession() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/v1/cualquier-cosa"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().doesNotExist("Set-Cookie"))
				.andReturn();

		assertThat(result.getRequest().getSession(false)).isNull();
	}

	@Test
	@WithMockUser
	@DisplayName("T01-12: CSRF está activo (un POST con sesión y sin token da 403; con token llega a la ruta)")
	void T01_12_csrfIsEnabled() throws Exception {
		mockMvc.perform(post("/api/v1/cualquier-cosa")).andExpect(status().isForbidden());
		mockMvc.perform(post("/api/v1/cualquier-cosa").with(csrf())).andExpect(status().isNotFound());
	}

}
