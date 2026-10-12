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
 * 004-03: arranque REAL con el perfil {@code dev} y sin variable: la demo está encendida por defecto y, como no es el
 * perfil prod, no hay WARN. Mismo límite que {@link DemoDataProdDefaultTests} con la variable del equipo.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = "spring.config.location=classpath:/application.yml")
@ActiveProfiles("dev")
@Import(TestcontainersConfiguration.class)
class DemoDataDevDefaultTests {

	@Autowired
	private Environment environment;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("004-03: en el perfil dev la demo está encendida por defecto y carga las 12 vacantes")
	void t004_03_devHasDemoDataByDefault(CapturedOutput output) {
		assertThat(environment.getProperty("talenti.demo-data.enabled", Boolean.class)).isTrue();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM vacancy WHERE status = 'OPEN'", Integer.class))
				.isEqualTo(12);
		assertThat(output.getAll()).contains("Datos de ejemplo activados").doesNotContain("ATENCIÓN: los datos de ejemplo");
	}

}
