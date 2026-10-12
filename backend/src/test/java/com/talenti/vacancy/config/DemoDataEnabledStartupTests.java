package com.talenti.vacancy.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.talenti.TestcontainersConfiguration;

/**
 * 004-03: arranque REAL con la demo encendida por la variable {@code TALENTI_DEMO_DATA} (como la enciende
 * Compose) y el perfil {@code prod}, solo con el {@code application.yml} principal: las empresas y vacantes se cargan
 * con el segundo Flyway, su historial va aparte del del esquema, y el arranque deja un WARN claro (B1).
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = { "spring.config.location=classpath:/application.yml", "TALENTI_DEMO_DATA=true" })
@ActiveProfiles("prod")
@Import(TestcontainersConfiguration.class)
class DemoDataEnabledStartupTests {

	@Autowired
	private Environment environment;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("004-03: TALENTI_DEMO_DATA=true sobre el perfil prod carga la demo con su propio historial")
	void t004_03_variableEnablesTheDemoOnProd() {
		assertThat(environment.getActiveProfiles()).containsExactly("prod");
		assertThat(environment.getProperty("talenti.demo-data.enabled", Boolean.class)).isTrue();

		assertThat(jdbc.queryForObject("SELECT count(*) FROM company", Integer.class)).isEqualTo(8);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM vacancy WHERE status = 'OPEN'", Integer.class))
				.isEqualTo(12);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM vacancy_requirement", Integer.class)).isEqualTo(60);
		assertThat(jdbc.queryForObject(
				"SELECT success FROM flyway_schema_history_demo WHERE version = '1000'", Boolean.class)).isTrue();
		// El historial del esquema nunca ve la V1000: por eso una V4 posterior se aplica sin problema.
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM flyway_schema_history WHERE version ~ '^[0-9]+$' AND version::int >= 1000",
				Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT max(version::int) FROM flyway_schema_history WHERE version IS NOT NULL",
				Integer.class)).isEqualTo(3);
	}

	@Test
	@DisplayName("004-03: con el perfil prod y la demo encendida, el arranque escribe un WARN claro")
	void t004_03_warnsWhenDemoIsOnWithProd(CapturedOutput output) {
		assertThat(output.getAll()).contains("WARN").contains("ATENCIÓN: los datos de ejemplo FICTICIOS están ACTIVADOS")
				.contains("perfil prod");
		long warnLines = output.getAll().lines().filter(line -> line.contains("ATENCIÓN: los datos de ejemplo"))
				.filter(line -> line.contains(" WARN ")).count();
		assertThat(warnLines).as("una línea de nivel WARN").isEqualTo(1);
	}

}
