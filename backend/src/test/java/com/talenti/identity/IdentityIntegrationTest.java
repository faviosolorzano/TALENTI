package com.talenti.identity;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.talenti.TestcontainersConfiguration;
import com.talenti.identity.service.LegalDocumentService;
import com.talenti.support.ApiClient;
import com.talenti.support.MutableClock;
import com.talenti.support.TestClockConfiguration;

/**
 * Base de las pruebas de integración de identity: aplicación completa con servidor HTTP real, PostgreSQL real
 * (Testcontainers) y un reloj que las pruebas pueden adelantar. Todos los datos de prueba son inventados y las
 * contraseñas se generan al azar en cada ejecución.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({ TestcontainersConfiguration.class, TestClockConfiguration.class })
public abstract class IdentityIntegrationTest {

	@LocalServerPort
	protected int port;

	@Autowired
	protected JdbcTemplate jdbc;

	@Autowired
	protected MutableClock clock;

	@Autowired
	protected LegalDocumentService legal;

	@Autowired
	protected PasswordEncoder passwordEncoder;

	protected ApiClient client() {
		return new ApiClient(port);
	}

	protected static String uniqueEmail() {
		return "estudiante." + UUID.randomUUID().toString().substring(0, 12) + "@ejemplo.test";
	}

	/** Cumple la política: letras, números y largo suficiente. */
	protected static String validPassword() {
		return "Aa1" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}

	protected Map<String, Object> registerBody(String email, String password) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", password);
		body.put("legalAccepted", true);
		body.put("legalVersion", legal.currentVersion());
		return body;
	}

	protected static Map<String, Object> loginBody(String email, String password) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("email", email);
		body.put("password", password);
		return body;
	}

	/** Registra un estudiante nuevo con su propio cliente (queda con la sesión abierta) y lo devuelve. */
	protected RegisteredStudent registerStudent() {
		ApiClient api = client();
		String email = uniqueEmail();
		String password = validPassword();
		ApiClient.Response response = api.post("/api/v1/auth/register", registerBody(email, password));
		if (response.status() != 201) {
			throw new IllegalStateException("El registro de prueba falló con " + response.status());
		}
		return new RegisteredStudent(api, UUID.fromString(response.json().get("id").asString()), email, password);
	}

	protected record RegisteredStudent(ApiClient api, UUID id, String email, String password) {
	}

	/** Crea directamente en la base un usuario con otro rol (no hay semilla ni registro público para ellos). */
	protected CreatedUser createUser(Role role) {
		String email = uniqueEmail();
		String password = validPassword();
		UUID id = UUID.randomUUID();
		OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		jdbc.update("""
				INSERT INTO app_user (id, email, password_hash, role, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?)""", id, email, passwordEncoder.encode(password), role.name(), now, now);
		return new CreatedUser(id, email, password);
	}

	protected record CreatedUser(UUID id, String email, String password) {
	}

	protected int count(String sql, Object... args) {
		Integer value = jdbc.queryForObject(sql, Integer.class, args);
		return value == null ? 0 : value;
	}

	protected int usersWithEmail(String email) {
		return count("SELECT count(*) FROM app_user WHERE email = ?", email.strip().toLowerCase());
	}

}
