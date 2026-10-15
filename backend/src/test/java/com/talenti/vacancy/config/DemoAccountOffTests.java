package com.talenti.vacancy.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import com.talenti.support.EmptyDatabaseConfiguration;

/** T-10: con la semilla apagada (el valor por defecto) la cuenta de demostración no existe. */
@SpringBootTest(properties = "talenti.demo-data.enabled=false")
@Import(EmptyDatabaseConfiguration.class)
class DemoAccountOffTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("T-10: con la semilla apagada no existe la cuenta de demostración ni su perfil")
	void t10_noDemoAccountWhenOff() {
		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE email = 'demo@example.test'", Integer.class))
				.isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM student_profile", Integer.class)).isZero();
	}

}
