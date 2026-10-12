package com.talenti.vacancy.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.talenti.support.EmptyDatabaseConfiguration;

/**
 * 004-03: arranque REAL con el perfil {@code prod} y solo el {@code application.yml} principal (sin el archivo de
 * pruebas, que apaga la demo por su cuenta): la semilla está apagada por defecto, no se crea ni se ejecuta nada de
 * la demo, y el Flyway del esquema queda estricto.
 *
 * <p>Límite: si el equipo que corre la prueba tiene la variable de entorno {@code TALENTI_DEMO_DATA} definida, esa
 * variable manda (es lo que se quiere probar) y la prueba fallaría; en CI no está definida.
 */
@SpringBootTest(properties = "spring.config.location=classpath:/application.yml")
@ActiveProfiles("prod")
@Import(EmptyDatabaseConfiguration.class)
class DemoDataProdDefaultTests {

	@Autowired
	private Environment environment;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("004-03: con el perfil prod y sin variable, la semilla está apagada y no se carga nada de la demo")
	void t004_03_prodStartsWithoutDemoData() {
		assertThat(environment.getActiveProfiles()).containsExactly("prod");
		assertThat(environment.getProperty("server.shutdown")).as("es el application.yml principal").isEqualTo("graceful");
		assertThat(environment.getProperty("talenti.identity.password.bcrypt-cost")).as("no es el archivo de pruebas")
				.isEqualTo("12");
		assertThat(environment.getProperty("talenti.demo-data.enabled", Boolean.class)).isFalse();

		assertThat(context.containsBean("demoDataMigrator")).isFalse();
		assertThat(jdbc.queryForObject("SELECT to_regclass('flyway_schema_history_demo')::text", String.class))
				.as("ni siquiera se crea el historial de la demo").isNull();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM company", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM vacancy", Integer.class)).isZero();
	}

	@Test
	@DisplayName("004-03: el Flyway del esquema es estricto: sin ignore-migration-patterns ni out-of-order")
	void t004_03_schemaFlywayIsStrict() {
		assertThat(environment.getProperty("spring.flyway.ignore-migration-patterns")).isNull();
		assertThat(environment.getProperty("spring.flyway.out-of-order", Boolean.class, false)).isFalse();
		assertThat(environment.getProperty("spring.flyway.locations")).isEqualTo("classpath:db/migration");
	}

}
