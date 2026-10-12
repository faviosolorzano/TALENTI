package com.talenti.vacancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.talenti.TestcontainersConfiguration;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;
import com.talenti.support.EmptyDatabaseConfiguration;

import tools.jackson.databind.JsonNode;

/**
 * Esquema y semilla APAGADA sobre una base de datos aparte y vacía (004-01, 004-02 y 004-03 con
 * {@code talenti.demo-data.enabled=false}). Se conecta con el rol de la aplicación, que no es superusuario.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "talenti.demo-data.enabled=false")
@Import(EmptyDatabaseConfiguration.class)
class VacancySchemaTests {

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbc;

	// ------------------------------------------------------------------------------------------ 004-01

	@Test
	@DisplayName("004-01: con una base vacía y el rol de la aplicación (sin superusuario), Flyway crea las cuatro tablas")
	void t004_01_flywayCreatesTheTables() {
		List<String> tables = jdbc.queryForList("""
				SELECT table_name FROM information_schema.tables
				WHERE table_schema = 'public' AND table_name IN ('company', 'vacancy', 'skill', 'vacancy_requirement')
				ORDER BY table_name""", String.class);
		Map<String, Object> role = jdbc.queryForMap(
				"SELECT rolname, rolsuper, rolcreaterole, rolcreatedb FROM pg_roles WHERE rolname = current_user");

		assertThat(tables).containsExactly("company", "skill", "vacancy", "vacancy_requirement");
		assertThat(role.get("rolname")).isEqualTo(TestcontainersConfiguration.APP_USER);
		assertThat(role.get("rolsuper")).isEqualTo(false);
		assertThat(jdbc.queryForObject("SELECT success FROM flyway_schema_history WHERE version = '3'", Boolean.class))
				.isTrue();
		assertThat(jdbc.queryForObject(
				"SELECT tableowner FROM pg_tables WHERE tablename = 'vacancy'", String.class))
				.isEqualTo(TestcontainersConfiguration.APP_USER);
	}

	@Test
	@DisplayName("004-01: claves UUID y fechas con zona horaria (timestamptz) en las cuatro tablas")
	void t004_01_uuidKeysAndTimestamptz() {
		List<String> notUuid = jdbc.queryForList("""
				SELECT table_name || '.' || column_name FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name IN ('company', 'vacancy', 'skill', 'vacancy_requirement')
				  AND column_name IN ('id', 'company_id', 'vacancy_id', 'skill_id') AND data_type <> 'uuid'""", String.class);
		List<String> notTimestamptz = jdbc.queryForList("""
				SELECT table_name || '.' || column_name FROM information_schema.columns
				WHERE table_schema = 'public' AND table_name IN ('company', 'vacancy', 'skill', 'vacancy_requirement')
				  AND column_name IN ('created_at', 'updated_at', 'published_at')
				  AND data_type <> 'timestamp with time zone'""", String.class);

		assertThat(notUuid).isEmpty();
		assertThat(notTimestamptz).isEmpty();
	}

	@Test
	@DisplayName("004-01: la extensión unaccent la creó el administrador de la base, no el rol de la aplicación, y funciona")
	void t004_01_unaccentCreatedByTheAdministrator() {
		Map<String, Object> extension = jdbc.queryForMap("""
				SELECT r.rolname, r.rolsuper FROM pg_extension e JOIN pg_roles r ON r.oid = e.extowner
				WHERE e.extname = 'unaccent'""");

		assertThat(extension.get("rolsuper")).as("creada por un superusuario").isEqualTo(true);
		assertThat(extension.get("rolname")).isNotEqualTo(TestcontainersConfiguration.APP_USER);
		assertThat(jdbc.queryForObject("SELECT has_database_privilege(current_user, current_database(), 'CREATE')",
				Boolean.class)).as("la aplicación no puede crear extensiones").isFalse();
		assertThat(jdbc.queryForObject("SELECT unaccent('Programación Cóndor ñandú')", String.class))
				.isEqualTo("Programacion Condor nandu");
		assertThat(jdbc.queryForObject("SELECT 'Programación' ILIKE unaccent('%PROGRAMACION%') ", Boolean.class))
				.as("ILIKE sin unaccent no encuentra").isFalse();
		assertThat(jdbc.queryForObject("SELECT unaccent('Programación') ILIKE unaccent('%PROGRAMACIÓN%')",
				Boolean.class)).isTrue();
	}

	@Test
	@DisplayName("004-01: si falta la extensión unaccent, la migración falla con un mensaje claro")
	void t004_01_migrationFailsClearlyWithoutUnaccent() {
		// Un PostgreSQL SIN el script db/init: no existe unaccent. Aquí el usuario sí es superusuario, para que
		// V1 (pgvector) pase y la que falle sea la comprobación de V3.
		try (PostgreSQLContainer plain = new PostgreSQLContainer(TestcontainersConfiguration.PGVECTOR_IMAGE)) {
			plain.start();
			Flyway flyway = Flyway.configure()
					.dataSource(plain.getJdbcUrl(), plain.getUsername(), plain.getPassword())
					.locations("classpath:db/migration").load();

			assertThatThrownBy(flyway::migrate).isInstanceOf(FlywayException.class)
					.hasMessageContaining("unaccent")
					.hasMessageContaining("docker compose down -v")
					.hasMessageContaining("V3");
			assertThat(new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
					plain.getJdbcUrl(), plain.getUsername(), plain.getPassword()))
					.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'vacancy'",
							Integer.class)).as("la migración no deja la tabla a medias").isZero();
		}
	}

	@Test
	@DisplayName("004-01: las restricciones de company y skill")
	void t004_01_companyAndSkillConstraints() {
		assertViolation("uq_company_name", tx -> {
			insertCompany(tx, "Empresa repetida");
			insertCompany(tx, "Empresa repetida");
		});
		assertViolation("ck_company_name_not_blank", tx -> insertCompany(tx, "   "));
		assertViolation("uq_skill_slug", tx -> {
			tx.update("INSERT INTO skill (slug, name) VALUES ('nueva', 'Nueva uno')");
			tx.update("INSERT INTO skill (slug, name) VALUES ('nueva', 'Nueva dos')");
		});
		assertViolation("uq_skill_name", tx -> {
			tx.update("INSERT INTO skill (slug, name) VALUES ('nueva-a', 'Nombre repetido')");
			tx.update("INSERT INTO skill (slug, name) VALUES ('nueva-b', 'Nombre repetido')");
		});
		assertViolation("ck_skill_slug_format", tx -> tx.update("INSERT INTO skill (slug, name) VALUES ('Mal Slug', 'X')"));
		assertViolation("ck_skill_slug_format", tx -> tx.update("INSERT INTO skill (slug, name) VALUES ('c#', 'Y')"));
	}

	@Test
	@DisplayName("004-01: las restricciones de vacancy (modalidad, estado, horas y ciudad según modalidad)")
	void t004_01_vacancyConstraints() {
		assertViolation("ck_vacancy_modality", tx -> insertVacancy(tx, "FOO", "Lima", 10, "OPEN"));
		assertViolation("ck_vacancy_status", tx -> insertVacancy(tx, "ONSITE", "Lima", 10, "DRAFT"));
		assertViolation("ck_vacancy_hours_per_week", tx -> insertVacancy(tx, "ONSITE", "Lima", 0, "OPEN"));
		assertViolation("ck_vacancy_hours_per_week", tx -> insertVacancy(tx, "ONSITE", "Lima", 41, "OPEN"));
		assertViolation("ck_vacancy_location", tx -> insertVacancy(tx, "REMOTE", "Lima", 10, "OPEN"));
		assertViolation("ck_vacancy_location", tx -> insertVacancy(tx, "ONSITE", null, 10, "OPEN"));
		assertViolation("ck_vacancy_location", tx -> insertVacancy(tx, "HYBRID", "  ", 10, "OPEN"));
		// Y los casos válidos pasan (las filas se deshacen al terminar la transacción).
		inRollbackTransaction(tx -> {
			insertVacancy(tx, "REMOTE", null, 1, "OPEN");
			insertVacancy(tx, "ONSITE", "Lima", 40, "CLOSED");
			insertVacancy(tx, "HYBRID", "Arequipa", 20, "OPEN");
		});
	}

	@Test
	@DisplayName("004-01: las restricciones de vacancy_requirement, las claves foráneas y el borrado en cascada")
	void t004_01_requirementConstraintsAndForeignKeys() {
		assertViolation("ck_vacancy_requirement_min_level", tx -> insertRequirement(tx, "REQUIRED", 0));
		assertViolation("ck_vacancy_requirement_min_level", tx -> insertRequirement(tx, "REQUIRED", 4));
		assertViolation("ck_vacancy_requirement_kind", tx -> insertRequirement(tx, "OPTIONAL", 1));
		assertViolation("uq_vacancy_requirement", tx -> {
			UUID vacancy = insertVacancy(tx, "REMOTE", null, 10, "OPEN");
			tx.update("INSERT INTO vacancy_requirement (vacancy_id, skill_id, kind, min_level) "
					+ "SELECT ?, id, 'REQUIRED', 1 FROM skill WHERE slug = 'java'", vacancy);
			tx.update("INSERT INTO vacancy_requirement (vacancy_id, skill_id, kind, min_level) "
					+ "SELECT ?, id, 'DESIRABLE', 2 FROM skill WHERE slug = 'java'", vacancy);
		});
		// ON DELETE RESTRICT: no se borra una empresa con vacantes ni una habilidad en uso.
		assertViolation("foreign key constraint", tx -> {
			UUID company = insertCompany(tx, "Empresa con vacante");
			tx.update("""
					INSERT INTO vacancy (company_id, title, summary, description, modality, location, hours_per_week, published_at)
					VALUES (?, 'T', 'S', 'D', 'ONSITE', 'Lima', 10, now())""", company);
			tx.update("DELETE FROM company WHERE id = ?", company);
		});
		assertViolation("foreign key constraint", tx -> {
			insertRequirement(tx, "REQUIRED", 1);
			tx.update("DELETE FROM skill WHERE slug = 'java'");
		});
		// ON DELETE CASCADE: al borrar la vacante se borran sus requisitos.
		inRollbackTransaction(tx -> {
			UUID vacancy = insertVacancy(tx, "REMOTE", null, 10, "OPEN");
			tx.update("INSERT INTO vacancy_requirement (vacancy_id, skill_id, kind, min_level) "
					+ "SELECT ?, id, 'REQUIRED', 2 FROM skill WHERE slug = 'java'", vacancy);
			assertThat(tx.queryForObject("SELECT count(*) FROM vacancy_requirement WHERE vacancy_id = ?", Integer.class,
					vacancy)).isEqualTo(1);

			tx.update("DELETE FROM vacancy WHERE id = ?", vacancy);

			assertThat(tx.queryForObject("SELECT count(*) FROM vacancy_requirement WHERE vacancy_id = ?", Integer.class,
					vacancy)).isZero();
		});
	}

	@Test
	@DisplayName("004-01: existen los índices de (status, published_at desc) y de company_id")
	void t004_01_indexes() {
		List<String> indexes = jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE tablename = 'vacancy'",
				String.class);

		assertThat(indexes).anyMatch(def -> def.contains("(status, published_at DESC)"));
		assertThat(indexes).anyMatch(def -> def.contains("(company_id)"));
	}

	// ------------------------------------------------------------------------------------------ 004-02

	@Test
	@DisplayName("004-02: el catálogo está en una migración de esquema: con la semilla apagada igual hay unas 30 habilidades")
	void t004_02_catalogComesWithTheSchema() {
		Integer skills = jdbc.queryForObject("SELECT count(*) FROM skill", Integer.class);
		Integer aliased = jdbc.queryForObject("SELECT count(*) FROM skill WHERE cardinality(aliases) > 0", Integer.class);
		Integer fromSchemaMigration = jdbc.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version = '3' AND script LIKE 'V3__%'", Integer.class);

		assertThat(skills).isBetween(28, 36);
		assertThat(aliased).isGreaterThanOrEqualTo(20);
		assertThat(fromSchemaMigration).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT count(DISTINCT slug) FROM skill", Integer.class)).isEqualTo(skills);
	}

	// ------------------------------------------------------------------------------------------ 004-03

	@Test
	@DisplayName("004-03: con la semilla apagada no hay empresas ni vacantes ni migraciones V1000+")
	void t004_03_seedOffLoadsNothing() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM company", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM vacancy", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM vacancy_requirement", Integer.class)).isZero();
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version ~ '^[0-9]+$' AND version::int >= 1000",
				Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT to_regclass('flyway_schema_history_demo')::text", String.class))
				.as("con la demo apagada ni se crea el historial de la demo").isNull();
	}

	@Test
	@DisplayName("004-03: con la semilla apagada el resto de la API responde con listas vacías y openCount 0")
	void t004_03_apiIsEmptyButHealthy() {
		ApiClient visitor = new ApiClient(port);

		JsonNode page = get(visitor, "/api/v1/vacancies");
		assertThat(page.get("items")).isEmpty();
		assertThat(page.get("totalItems").asInt()).isZero();
		assertThat(page.get("totalPages").asInt()).isZero();
		assertThat(page.get("page").asInt()).isEqualTo(1);
		assertThat(page.get("size").asInt()).isEqualTo(9);

		assertThat(get(visitor, "/api/v1/vacancies/featured").get("items")).isEmpty();
		assertThat(get(visitor, "/api/v1/companies/featured").get("items")).isEmpty();
		JsonNode filters = get(visitor, "/api/v1/vacancies/filters");
		assertThat(filters.get("openCount").asInt()).isZero();
		assertThat(filters.get("modalities")).isEmpty();
		assertThat(filters.get("locations")).isEmpty();
		assertThat(filters.get("skills")).isEmpty();
		assertThat(get(visitor, "/api/v1/vacancies?q=java&modality=REMOTE&location=Lima&skill=java&page=4")
				.get("items")).isEmpty();
		assertThat(visitor.get("/api/v1/vacancies/d0000000-0000-4000-8000-000000000001").status()).isEqualTo(404);
	}

	// ------------------------------------------------------------------------------------------ ayudas

	private static JsonNode get(ApiClient visitor, String path) {
		Response response = visitor.get(path);
		assertThat(response.status()).as(path).isEqualTo(200);
		return response.json();
	}

	/** Ejecuta el bloque en su propia transacción y la deshace siempre, para no dejar filas. */
	private void inRollbackTransaction(Consumer<JdbcTemplate> block) {
		// Gestor sobre el mismo DataSource que el JdbcTemplate: las sentencias participan en esta transacción.
		new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status -> {
			status.setRollbackOnly();
			block.accept(jdbc);
		});
	}

	/** El bloque debe fallar por una restricción cuyo nombre empieza por {@code constraintPrefix}. */
	private void assertViolation(String constraintPrefix, Consumer<JdbcTemplate> block) {
		assertThatThrownBy(() -> inRollbackTransaction(block)).isInstanceOf(DataIntegrityViolationException.class)
				.satisfies(error -> assertThat(error.getMessage()).contains(constraintPrefix));
	}

	private static UUID insertCompany(JdbcTemplate tx, String name) {
		UUID id = UUID.randomUUID();
		tx.update("INSERT INTO company (id, name, city, short_description) VALUES (?, ?, 'Lima', 'Empresa de prueba.')",
				id, name);
		return id;
	}

	private static UUID insertVacancy(JdbcTemplate tx, String modality, String location, int hours, String status) {
		UUID company = insertCompany(tx, "Empresa " + UUID.randomUUID());
		UUID id = UUID.randomUUID();
		tx.update("""
				INSERT INTO vacancy (id, company_id, title, summary, description, modality, location, hours_per_week,
				                     published_at, status)
				VALUES (?, ?, 'Titulo', 'Resumen', 'Descripcion', ?, ?, ?, now(), ?)""", id, company, modality,
				location, hours, status);
		return id;
	}

	private static void insertRequirement(JdbcTemplate tx, String kind, int level) {
		UUID vacancy = insertVacancy(tx, "REMOTE", null, 10, "OPEN");
		tx.update("INSERT INTO vacancy_requirement (vacancy_id, skill_id, kind, min_level) "
				+ "SELECT ?, id, ?, ? FROM skill WHERE slug = 'java'", vacancy, kind, level);
	}

}
