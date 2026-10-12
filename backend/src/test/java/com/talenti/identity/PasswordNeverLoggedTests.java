package com.talenti.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

/**
 * 001-20: la contraseña no queda en claro ni en los registros (logs) ni en la base de datos. Se sube el nivel de
 * registro al máximo en las partes que tocan las credenciales para que la prueba sea exigente.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"logging.level.com.talenti=TRACE",
		"logging.level.org.springframework.security=TRACE",
		"logging.level.org.springframework.web=TRACE",
		"logging.level.org.springframework.orm.jpa=DEBUG",
		"logging.level.org.springframework.transaction=TRACE",
		"logging.level.org.hibernate.SQL=DEBUG",
		"spring.mvc.log-request-details=true" })
class PasswordNeverLoggedTests extends IdentityIntegrationTest {

	@Test
	@DisplayName("001-20: tras registrar, ingresar con error y ingresar, la contraseña no está en los registros ni en la base de datos")
	void t001_20_passwordIsNeverInLogsOrDatabase(CapturedOutput output) {
		ApiClient api = client();
		String email = uniqueEmail();
		String password = validPassword();
		String wrongPassword = validPassword();

		Response register = api.post("/api/v1/auth/register", registerBody(email, password));
		Response failed = client().post("/api/v1/auth/login", loginBody(email, wrongPassword));
		Response login = client().post("/api/v1/auth/login", loginBody(email, password));
		Response rejected = client().post("/api/v1/auth/register", registerBody(uniqueEmail(), "corta1"));

		assertThat(register.status()).isEqualTo(201);
		assertThat(failed.status()).isEqualTo(401);
		assertThat(login.status()).isEqualTo(200);
		assertThat(rejected.status()).isEqualTo(422);

		// Registros: ni las contraseñas (buena, errónea, rechazada) ni el correo.
		assertThat(output.getAll()).as("registros de la aplicación").isNotEmpty()
				.doesNotContain(password).doesNotContain(wrongPassword).doesNotContain("corta1")
				.doesNotContain(email);

		// Base de datos: solo existe el hash adaptativo.
		String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE email = ?", String.class, email);
		assertThat(hash).matches("^\\$2[aby]\\$\\d{2}\\$.{53}$").doesNotContain(password);
		assertThat(passwordEncoder.matches(password, hash)).isTrue();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM app_user WHERE password_hash = ?", Integer.class, password))
				.isZero();
		List<String> auditDetails = jdbc.queryForList("SELECT details::text FROM audit_event", String.class);
		assertThat(auditDetails).noneMatch(details -> details.contains(password));
	}

}
