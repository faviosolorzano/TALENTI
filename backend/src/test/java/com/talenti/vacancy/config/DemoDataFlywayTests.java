package com.talenti.vacancy.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.talenti.TestcontainersConfiguration;

/**
 * 004-03: los dos Flyway sobre una base propia, EN ORDEN (cada prueba parte de lo que dejó la anterior). Se usa la
 * misma configuración que la aplicación: el Flyway del esquema con sus valores por defecto de Flyway (estricto) y el
 * de la demo construido por {@link DemoDataMigrator#flyway(DataSource)}. Las migraciones "V4" y "V5" de prueba están
 * en {@code src/test/resources/db/probe-a} y {@code probe-b}, nunca en {@code db/migration}.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DemoDataFlywayTests {

	private static PostgreSQLContainer database;

	private static DataSource dataSource;

	private static JdbcTemplate jdbc;

	@BeforeAll
	static void startDatabase() {
		// Mismo PostgreSQL, mismo script de inicialización y rol de la aplicación sin superusuario.
		database = TestcontainersConfiguration.newContainer();
		database.start();
		dataSource = new DriverManagerDataSource(database.getJdbcUrl(), TestcontainersConfiguration.APP_USER,
				TestcontainersConfiguration.APP_PASSWORD);
		jdbc = new JdbcTemplate(dataSource);
	}

	@AfterAll
	static void stopDatabase() {
		database.stop();
	}

	private static Flyway schema(String... extraLocations) {
		String[] locations = new String[extraLocations.length + 1];
		locations[0] = "classpath:db/migration";
		System.arraycopy(extraLocations, 0, locations, 1, extraLocations.length);
		return Flyway.configure().dataSource(dataSource).locations(locations).cleanDisabled(true).load();
	}

	private static int count(String sql) {
		Integer value = jdbc.queryForObject(sql, Integer.class);
		return value == null ? 0 : value;
	}

	@Test
	@Order(1)
	@DisplayName("004-03: con la demo apagada solo corre el esquema: ni se crea ni se ejecuta nada de la demo")
	void t004_03_demoOffCreatesNothing() {
		schema().migrate();

		assertThat(count("SELECT count(*) FROM company")).isZero();
		assertThat(count("SELECT count(*) FROM vacancy")).isZero();
		assertThat(jdbc.queryForObject("SELECT to_regclass('flyway_schema_history_demo')::text", String.class)).isNull();
		assertThat(count("SELECT count(*) FROM skill")).isBetween(28, 36);
	}

	@Test
	@Order(2)
	@DisplayName("004-03: con la demo encendida las empresas y vacantes se cargan, con historial propio, y repetir no cambia nada")
	void t004_03_demoOnLoadsWithItsOwnHistory() {
		new DemoDataMigrator(dataSource).migrate();

		assertThat(count("SELECT count(*) FROM company")).isEqualTo(8);
		assertThat(count("SELECT count(*) FROM company WHERE featured")).isEqualTo(4);
		assertThat(count("SELECT count(*) FROM vacancy WHERE status = 'OPEN'")).isEqualTo(12);
		assertThat(count("SELECT count(*) FROM vacancy WHERE featured")).isEqualTo(4);
		assertThat(count("SELECT count(*) FROM vacancy_requirement")).isEqualTo(60);
		assertThat(count("SELECT count(*) FROM flyway_schema_history_demo WHERE version = '1000' AND success")).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version ~ '^[0-9]+$' AND version::int >= 1000"))
				.as("el historial del esquema no ve la demo").isZero();

		new DemoDataMigrator(dataSource).migrate();

		assertThat(count("SELECT count(*) FROM company")).as("repetir no duplica").isEqualTo(8);
		assertThat(count("SELECT count(*) FROM vacancy")).isEqualTo(12);
	}

	@Test
	@Order(3)
	@DisplayName("004-03: apagar la demo después de encenderla no rompe el esquema (no hay nada que ignorar)")
	void t004_03_demoTurnedOffAfterOnStillStarts() {
		schema().validate();
		schema().migrate();

		assertThat(count("SELECT count(*) FROM company")).as("los datos cargados se quedan").isEqualTo(8);
	}

	@Test
	@Order(4)
	@DisplayName("004-03: una migración nueva del esquema (V4 de prueba) se aplica con la demo ya cargada")
	void t004_03_newSchemaMigrationAppliesWithDemoLoaded() {
		schema("classpath:db/probe-a").migrate();

		assertThat(jdbc.queryForObject("SELECT to_regclass('probe_a')::text", String.class)).isEqualTo("probe_a");
		assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success")).isEqualTo(1);
		schema("classpath:db/probe-a").validate();
		new DemoDataMigrator(dataSource).migrate();
		assertThat(count("SELECT count(*) FROM vacancy")).isEqualTo(12);
	}

	@Test
	@Order(5)
	@DisplayName("004-03: el Flyway del esquema sigue siendo estricto: una migración aplicada que ya no está hace fallar el arranque")
	void t004_03_missingSchemaMigrationFails() {
		// V4 ya está aplicada. Se agrega la V5 y se quita la V4: Flyway debe negarse en vez de ignorarlo.
		schema("classpath:db/probe-a", "classpath:db/probe-b").migrate();

		assertThatThrownBy(() -> schema("classpath:db/probe-b").migrate()).isInstanceOf(FlywayValidateException.class)
				.hasMessageContaining("applied migration not resolved locally");
		assertThatThrownBy(() -> schema("classpath:db/probe-b").validate()).isInstanceOf(FlywayValidateException.class);
		// Y la demo no cambia nada de eso: sigue cargada y su Flyway sano.
		new DemoDataMigrator(dataSource).migrate();
		assertThat(count("SELECT count(*) FROM company")).isEqualTo(8);
	}

}
