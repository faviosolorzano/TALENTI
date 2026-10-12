package com.talenti.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/** Registro de un estudiante (spec 001): criterios 001-01 a 001-07, 001-19, 001-24 y 001-27. */
class RegistrationIntegrationTests extends IdentityIntegrationTest {

	private static final String REGISTER = "/api/v1/auth/register";

	@Test
	@DisplayName("001-01: registrar crea un STUDENT, guarda TERMS y ACCOUNT_DATA con versión y fecha, y deja la sesión abierta")
	void t001_01_registerCreatesStudentWithConsentsAndSession() {
		ApiClient api = client();
		String email = uniqueEmail();

		Response response = api.post(REGISTER, registerBody(email, validPassword()));

		assertThat(response.status()).isEqualTo(201);
		JsonNode body = response.json();
		assertThat(body.get("email").asString()).isEqualTo(email);
		assertThat(body.get("role").asString()).isEqualTo("STUDENT");
		UUID id = UUID.fromString(body.get("id").asString());
		assertThat(body.has("passwordHash")).isFalse();
		assertThat(body.has("password")).isFalse();

		List<Map<String, Object>> consents = jdbc.queryForList(
				"SELECT purpose, consent_version, accepted_at FROM consent_record WHERE user_id = ? ORDER BY purpose", id);
		assertThat(consents).extracting(row -> row.get("purpose")).containsExactly("ACCOUNT_DATA", "TERMS");
		assertThat(consents).allSatisfy(row -> {
			assertThat(row.get("consent_version")).isEqualTo(legal.currentVersion());
			Object acceptedAt = row.get("accepted_at");
			Instant instant = acceptedAt instanceof Timestamp timestamp ? timestamp.toInstant()
					: ((OffsetDateTime) acceptedAt).toInstant();
			assertThat(Duration.between(instant, clock.instant()).abs()).isLessThan(Duration.ofSeconds(5));
		});

		// Quedó con la sesión iniciada: la cookie recibida abre "mi usuario" sin ingresar.
		assertThat(response.setCookie("JSESSIONID")).isPresent();
		Response me = api.get("/api/v1/auth/me");
		assertThat(me.status()).isEqualTo(200);
		assertThat(me.json().get("id").asString()).isEqualTo(id.toString());
		assertThat(me.json().get("legalAcceptancePending").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("001-02: sin marcar la casilla responde 422, explica que es obligatoria y no crea nada")
	void t001_02_withoutAcceptanceIsRejected() {
		String email = uniqueEmail();
		for (Object accepted : new Object[] { false, null }) {
			Map<String, Object> body = registerBody(email, validPassword());
			body.put("legalAccepted", accepted);

			Response response = client().post(REGISTER, body);

			assertThat(response.status()).isEqualTo(422);
			assertThat(response.json().get("code").asString()).isEqualTo("validation-failed");
			JsonNode error = response.json().get("errors").get(0);
			assertThat(error.get("field").asString()).isEqualTo("legalAccepted");
			assertThat(error.get("rule").asString()).isEqualTo("must-be-accepted");
			assertThat(error.get("message").asString()).contains("Debes aceptar");
		}
		assertThat(usersWithEmail(email)).isZero();
		assertThat(count("SELECT count(*) FROM consent_record c JOIN app_user u ON u.id = c.user_id WHERE u.email = ?",
				email)).isZero();
	}

	@Test
	@DisplayName("001-03: una versión legal que ya no es la vigente responde 409 y no crea nada")
	void t001_03_outdatedLegalVersionIsRejected() {
		String email = uniqueEmail();
		Map<String, Object> body = registerBody(email, validPassword());
		body.put("legalVersion", "v0");

		Response response = client().post(REGISTER, body);

		assertThat(response.status()).isEqualTo(409);
		assertThat(response.json().get("code").asString()).isEqualTo("legal-version-outdated");
		assertThat(response.json().get("detail").asString()).contains("Lee el texto actual");
		assertThat(usersWithEmail(email)).isZero();
	}

	@Test
	@DisplayName("001-04: un correo ya registrado (sin distinguir mayúsculas) responde 409 sin revelar datos de esa cuenta")
	void t001_04_duplicateEmailIsConflict() {
		RegisteredStudent existing = registerStudent();

		Response response = client().post(REGISTER, registerBody("  " + existing.email().toUpperCase() + " ", validPassword()));

		assertThat(response.status()).isEqualTo(409);
		assertThat(response.json().get("code").asString()).isEqualTo("email-already-registered");
		assertThat(response.json().get("detail").asString()).contains("ingresar");
		assertThat(response.body()).doesNotContain(existing.id().toString()).doesNotContain("STUDENT");
		assertThat(usersWithEmail(existing.email())).isEqualTo(1);
	}

	@Test
	@DisplayName("001-04: dos registros simultáneos con el mismo correo crean una sola cuenta y el otro recibe 409")
	void t001_04_simultaneousRegistrationsCreateOneAccount() throws Exception {
		String email = uniqueEmail();
		int attempts = 6;
		ExecutorService pool = Executors.newFixedThreadPool(attempts);
		CountDownLatch ready = new CountDownLatch(attempts);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Callable<Response>> jobs = new ArrayList<>();
			for (int i = 0; i < attempts; i++) {
				ApiClient api = client();
				api.get("/api/v1/auth/csrf");
				Map<String, Object> body = registerBody(email, validPassword());
				jobs.add(() -> {
					ready.countDown();
					go.await();
					return api.post(REGISTER, body);
				});
			}
			List<Future<Response>> futures = new ArrayList<>();
			for (Callable<Response> job : jobs) {
				futures.add(pool.submit(job));
			}
			ready.await();
			go.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Response> future : futures) {
				statuses.add(future.get().status());
			}

			assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
			assertThat(statuses).filteredOn(status -> status == 409).hasSize(attempts - 1);
			assertThat(usersWithEmail(email)).isEqualTo(1);
			assertThat(count("""
					SELECT count(*) FROM consent_record c JOIN app_user u ON u.id = c.user_id WHERE u.email = ?""", email))
					.isEqualTo(2);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("001-05: un correo mal formado o una contraseña que incumple la política responde 422 con el campo y la regla")
	void t001_05_invalidFieldsAreRejectedWithFieldAndRule() {
		assertRejected("no-es-un-correo", validPassword(), "email", "invalid-format");
		assertRejected("a@b", validPassword(), "email", "invalid-format");
		assertRejected(" ", validPassword(), "email", "required");
		assertRejected("x".repeat(250) + "@ejemplo.test", validPassword(), "email", "too-long");
		assertRejected(uniqueEmail(), "corta1", "password", "min-length");
		assertRejected(uniqueEmail(), "SoloLetrasPeroLargas", "password", "needs-digit");
		assertRejected(uniqueEmail(), "123456789012", "password", "needs-letter");
		assertRejected(uniqueEmail(), "A1" + "x".repeat(70), "password", "max-length");
		String sameAsEmail = "Persona1@ejemplo.test";
		assertRejected(sameAsEmail, sameAsEmail.toLowerCase(), "password", "same-as-email");
	}

	private void assertRejected(String email, String password, String field, String rule) {
		int usersBefore = count("SELECT count(*) FROM app_user");

		Response response = client().post(REGISTER, registerBody(email, password));

		assertThat(response.status()).as("%s / %s", field, rule).isEqualTo(422);
		assertThat(response.json().get("code").asString()).isEqualTo("validation-failed");
		List<String> violations = new ArrayList<>();
		for (JsonNode error : response.json().get("errors")) {
			violations.add(error.get("field").asString() + ":" + error.get("rule").asString());
			assertThat(error.get("message").asString()).isNotBlank();
		}
		assertThat(violations).contains(field + ":" + rule);
		assertThat(response.body()).doesNotContain(password);
		assertThat(count("SELECT count(*) FROM app_user")).isEqualTo(usersBefore);
	}

	@Test
	@DisplayName("001-05: los campos con un tamaño desmesurado se rechazan con 422 (too-long) antes de procesarlos")
	void t001_05_oversizedFieldsAreRejected() {
		int usersBefore = count("SELECT count(*) FROM app_user");
		Map<String, Object> longPassword = registerBody(uniqueEmail(), "A1" + "x".repeat(300));
		Map<String, Object> longVersion = registerBody(uniqueEmail(), validPassword());
		longVersion.put("legalVersion", "v".repeat(21));
		Map<String, Object> longEmail = registerBody("a".repeat(300) + "@ejemplo.test", validPassword());

		assertTooLong(client().post(REGISTER, longPassword), "password");
		assertTooLong(client().post(REGISTER, longVersion), "legalVersion");
		Response email = client().post(REGISTER, longEmail);
		assertTooLong(email, "email");
		assertThat(email.json().get("errors").get(0).get("message").asString()).contains("254");
		assertTooLong(client().post("/api/v1/auth/login", loginBody("a".repeat(300) + "@ejemplo.test", "Aa1")), "email");
		assertTooLong(client().post("/api/v1/auth/login", loginBody(uniqueEmail(), "A1" + "x".repeat(300))), "password");
		assertThat(count("SELECT count(*) FROM app_user")).isEqualTo(usersBefore);
	}

	private static void assertTooLong(Response response, String field) {
		assertThat(response.status()).as(field).isEqualTo(422);
		JsonNode error = response.json().get("errors").get(0);
		assertThat(error.get("field").asString()).isEqualTo(field);
		assertThat(error.get("rule").asString()).isEqualTo("too-long");
	}

	@Test
	@DisplayName("001-11b: más de 10 solicitudes de registro por minuto desde un mismo origen responden 429 con Retry-After; otro origen y el ingreso no se ven afectados")
	void t001_11b_moreThanTenRegisterRequestsPerMinuteAreLimited() {
		ApiClient api = client();
		Map<String, Object> invalid = registerBody("no-es-un-correo", validPassword());
		for (int i = 1; i <= 10; i++) {
			assertThat(api.post(REGISTER, invalid).status()).as("solicitud %d", i).isEqualTo(422);
		}

		Response limited = api.post(REGISTER, registerBody(uniqueEmail(), validPassword()));

		assertThat(limited.status()).isEqualTo(429);
		assertThat(limited.json().get("code").asString()).isEqualTo("too-many-requests");
		assertThat(limited.header("Content-Type")).contains("application/problem+json");
		assertThat(Long.parseLong(limited.header("Retry-After"))).isBetween(1L, 61L);
		// No se creó la cuenta de la solicitud limitada.
		// Otro origen no se ve afectado, y el ingreso del mismo origen tiene su propio cubo.
		assertThat(client().post(REGISTER, registerBody(uniqueEmail(), validPassword())).status()).isEqualTo(201);
		assertThat(api.post("/api/v1/auth/login", loginBody(uniqueEmail(), validPassword())).status()).isEqualTo(401);
		// Pasada la ventana, el origen vuelve a poder registrar.
		clock.advance(Duration.ofSeconds(61));
		assertThat(api.post(REGISTER, registerBody(uniqueEmail(), validPassword())).status()).isEqualTo(201);
	}

	@Test
	@DisplayName("001-05: el correo se recorta y se pasa a minúsculas antes de guardarse")
	void t001_05_emailIsTrimmedAndLowercased() {
		String email = uniqueEmail();

		Response response = client().post(REGISTER, registerBody("  " + email.toUpperCase() + "  ", validPassword()));

		assertThat(response.status()).isEqualTo(201);
		assertThat(response.json().get("email").asString()).isEqualTo(email);
		assertThat(usersWithEmail(email)).isEqualTo(1);
	}

	@Test
	@DisplayName("001-07: un role enviado en el registro se ignora y el usuario nace siempre STUDENT")
	void t001_07_roleInRequestIsIgnored() {
		String email = uniqueEmail();
		Map<String, Object> body = new LinkedHashMap<>(registerBody(email, validPassword()));
		body.put("role", "ADMIN");

		Response response = client().post(REGISTER, body);

		assertThat(response.status()).isEqualTo(201);
		assertThat(response.json().get("role").asString()).isEqualTo("STUDENT");
		assertThat(jdbc.queryForObject("SELECT role FROM app_user WHERE email = ?", String.class, email))
				.isEqualTo("STUDENT");
	}

	@Test
	@DisplayName("001-19: el registro deja USER_REGISTERED y un CONSENT_ACCEPTED por permiso (versión y finalidad), sin datos personales")
	void t001_19_registrationIsAuditedWithoutPersonalData() {
		String email = uniqueEmail();
		String password = validPassword();
		Response response = client().post(REGISTER, registerBody(email, password));
		UUID id = UUID.fromString(response.json().get("id").asString());

		List<Map<String, Object>> events = jdbc.queryForList("""
				SELECT event_type, actor_user_id, subject_type, subject_id, correlation_id, details::text AS details
				FROM audit_event WHERE actor_user_id = ? ORDER BY event_type, details::text""", id);

		assertThat(events).extracting(row -> row.get("event_type"))
				.containsExactly("CONSENT_ACCEPTED", "CONSENT_ACCEPTED", "USER_REGISTERED");
		assertThat(events.stream().filter(row -> row.get("event_type").equals("CONSENT_ACCEPTED"))
				.map(row -> row.get("details").toString()).toList())
				.anySatisfy(details -> assertThat(details).contains("\"TERMS\"").contains("\"v1\""))
				.anySatisfy(details -> assertThat(details).contains("\"ACCOUNT_DATA\"").contains("\"v1\""));
		assertThat(events).allSatisfy(row -> {
			assertThat(row.get("correlation_id")).isNotNull();
			String details = row.get("details").toString().toLowerCase();
			assertThat(details).doesNotContain(email.toLowerCase()).doesNotContain("@").doesNotContain("password");
			assertThat(details).doesNotContain(password.toLowerCase());
		});
	}

	@Test
	@DisplayName("001-24: app_user tiene email_verified_at (nula) y nadie queda bloqueado por no verificar el correo")
	void t001_24_emailVerifiedAtExistsAndIsNotRequired() {
		RegisteredStudent student = registerStudent();

		Map<String, Object> column = jdbc.queryForMap("""
				SELECT data_type, is_nullable FROM information_schema.columns
				WHERE table_name = 'app_user' AND column_name = 'email_verified_at'""");
		assertThat(column.get("data_type")).isEqualTo("timestamp with time zone");
		assertThat(column.get("is_nullable")).isEqualTo("YES");
		assertThat(jdbc.queryForObject("SELECT email_verified_at FROM app_user WHERE id = ?", Object.class, student.id()))
				.isNull();

		Response login = client().post("/api/v1/auth/login", loginBody(student.email(), student.password()));
		assertThat(login.status()).isEqualTo(200);
	}

	@Test
	@DisplayName("001-27: consent_record admite TERMS, ACCOUNT_DATA, CV_AI y VOICE_AI, y rechaza cualquier otra finalidad")
	void t001_27_consentRecordAcceptsAllFourPurposes() {
		RegisteredStudent student = registerStudent();
		OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);

		for (String purpose : List.of("TERMS", "ACCOUNT_DATA", "CV_AI", "VOICE_AI")) {
			int inserted = jdbc.update("""
					INSERT INTO consent_record (user_id, purpose, consent_version, accepted_at) VALUES (?, ?, 'v1', ?)""",
					student.id(), purpose, now);
			assertThat(inserted).isEqualTo(1);
		}
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO consent_record (user_id, purpose, consent_version, accepted_at) VALUES (?, 'OTRA', 'v1', ?)""",
				student.id(), now)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("001-01: el esquema impone el correo único y en minúsculas, y los tres roles")
	void t001_01_schemaConstraints() {
		RegisteredStudent student = registerStudent();
		OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO app_user (email, password_hash, role, created_at, updated_at) VALUES (?, 'x', 'STUDENT', ?, ?)""",
				student.email(), now, now)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO app_user (email, password_hash, role, created_at, updated_at) VALUES (?, 'x', 'STUDENT', ?, ?)""",
				"Mayusculas@Ejemplo.Test", now, now)).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO app_user (email, password_hash, role, created_at, updated_at) VALUES (?, 'x', 'ROOT', ?, ?)""",
				uniqueEmail(), now, now)).isInstanceOf(DataIntegrityViolationException.class);
	}

}
