package com.talenti.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.talenti.TestcontainersConfiguration;

/** 006-30: la migración V6 convierte display_name en first_names sin perder datos. */
class ProfilePersonalDataMigrationTests {

	private static PostgreSQLContainer database;

	private static DataSource dataSource;

	@BeforeAll
	static void startDatabase() {
		database = TestcontainersConfiguration.newContainer();
		database.start();
		dataSource = new DriverManagerDataSource(database.getJdbcUrl(), TestcontainersConfiguration.APP_USER,
				TestcontainersConfiguration.APP_PASSWORD);
	}

	@AfterAll
	static void stopDatabase() {
		database.stop();
	}

	@Test
	@DisplayName("006-30: una base con display_name en V5 pasa a V6 y conserva el nombre en first_names; los demás datos siguen igual")
	void t006_30_displayNameIsMigratedWithoutLosingData() {
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("5").cleanDisabled(true)
				.load().migrate();
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		UUID withName = UUID.randomUUID();
		UUID withoutName = UUID.randomUUID();
		for (UUID id : new UUID[] { withName, withoutName }) {
			jdbc.update("INSERT INTO app_user (id, email, password_hash, role, created_at, updated_at) VALUES (?, ?, 'x', 'STUDENT', now(), now())", id,
					id + "@prueba.pe");
		}
		jdbc.update("INSERT INTO student_profile (user_id, display_name, about, career) VALUES (?, 'Ana María', 'Una presentación válida.', 'Software')",
				withName);
		jdbc.update("INSERT INTO student_profile (user_id) VALUES (?)", withoutName);

		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").cleanDisabled(true).load().migrate();

		assertThat(jdbc.queryForObject("SELECT first_names FROM student_profile WHERE user_id = ?", String.class, withName))
				.isEqualTo("Ana María");
		assertThat(jdbc.queryForObject("SELECT about FROM student_profile WHERE user_id = ?", String.class, withName))
				.isEqualTo("Una presentación válida.");
		assertThat(jdbc.queryForObject("SELECT career FROM student_profile WHERE user_id = ?", String.class, withName))
				.isEqualTo("Software");
		assertThat(jdbc.queryForObject("SELECT first_names FROM student_profile WHERE user_id = ?", String.class,
				withoutName)).isNull();
		assertThat(jdbc.queryForObject("SELECT last_names || contact_email || phone FROM student_profile WHERE user_id = ?",
				String.class, withName)).isNull();
		assertThat(jdbc.queryForObject(
				"SELECT count(*) FROM information_schema.columns WHERE table_name = 'student_profile' AND column_name = 'display_name'",
				Integer.class)).isZero();
	}

}
