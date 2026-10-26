package com.talenti.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.identity.Role;
import com.talenti.matching.MatchingIntegrationTest;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/** API de postulaciones (spec 008): alta, duplicados, retiro, lista, detalle, resumen y seguridad. */
class ApplicationApiTests extends MatchingIntegrationTest {

	private static final String APPLICATIONS = "/api/v1/applications";

	private Response apply(ApiClient api, String vacancyId) {
		return api.post(APPLICATIONS, Map.of("vacancyId", vacancyId));
	}

	private String applyOk(ApiClient api, String vacancyId) {
		Response response = apply(api, vacancyId);
		assertThat(response.status()).as(response.body()).isEqualTo(201);
		return response.json().get("id").asString();
	}

	private static String code(Response response) {
		return response.json().get("code").asString();
	}

	// ------------------------------------------------------------------------------------------ 008-01

	/** Las postulaciones y las vacantes de prueba no deben quedar: otras pruebas comparten la misma base. */
	@org.junit.jupiter.api.AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM application");
		jdbc.update("DELETE FROM vacancy WHERE id::text NOT LIKE 'd0000000-%'");
	}

	@Test
	@DisplayName("008-01: Flyway crea las dos tablas con el índice único parcial y los checks")
	void t008_01_schema() {
		assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_name IN ('application', 'application_stage_event')"))
				.isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = 'uq_application_active'",
				String.class)).contains("UNIQUE").contains("WITHDRAWN").contains("EXPIRED");
		assertThat(count("SELECT count(*) FROM pg_constraint WHERE conname IN ('ck_application_status', 'ck_application_withdrawn_at')"))
				.isEqualTo(2);
	}

	// ------------------------------------------------------------------------------------------ 008-02 a 05

	@Test
	@DisplayName("008-02: postular a una vacante abierta da 201 SUBMITTED, con historial null a SUBMITTED y auditoría sin datos personales")
	void t008_02_submit() {
		RegisteredStudent student = registerStudent();

		Response response = apply(student.api(), BACKEND_JAVA);

		assertThat(response.status()).isEqualTo(201);
		assertThat(response.header("Cache-Control")).contains("no-store");
		JsonNode application = response.json();
		assertThat(application.get("status").asString()).isEqualTo("SUBMITTED");
		assertThat(application.get("withdrawnAt").isNull()).isTrue();
		assertThat(application.get("submittedAt").asString()).isNotBlank();
		assertThat(application.get("vacancy").get("title").asString()).isEqualTo("Practicante Backend Java");
		assertThat(application.get("vacancy").get("status").asString()).isEqualTo("OPEN");
		assertThat(application.get("vacancy").get("company").get("name").asString()).isNotBlank();
		assertThat(application.get("history")).hasSize(1);
		assertThat(application.get("history").get(0).get("status").asString()).isEqualTo("SUBMITTED");
		UUID id = UUID.fromString(application.get("id").asString());
		assertThat(jdbc.queryForObject("SELECT from_status IS NULL FROM application_stage_event WHERE application_id = ?",
				Boolean.class, id)).isTrue();
		Map<String, Object> audit = jdbc.queryForMap("SELECT actor_user_id, subject_type, details::text AS details "
				+ "FROM audit_event WHERE event_type = 'APPLICATION_SUBMITTED' AND subject_id = ?", id);
		assertThat(audit.get("actor_user_id")).isEqualTo(student.id());
		assertThat(audit.get("subject_type")).isEqualTo("application");
		assertThat((String) audit.get("details")).contains(BACKEND_JAVA).doesNotContain(student.email());
	}

	@Test
	@DisplayName("008-03: postular otra vez a la misma vacante da 409 application-already-exists y no crea otra")
	void t008_03_duplicate() {
		RegisteredStudent student = registerStudent();
		applyOk(student.api(), BACKEND_JAVA);

		Response again = apply(student.api(), BACKEND_JAVA);

		assertThat(again.status()).isEqualTo(409);
		assertThat(code(again)).isEqualTo("application-already-exists");
		assertThat(count("SELECT count(*) FROM application WHERE user_id = ?", student.id())).isEqualTo(1);
	}

	@Test
	@DisplayName("008-03: con dos solicitudes simultáneas exactamente una responde 201")
	void t008_03_concurrentDuplicates() throws Exception {
		RegisteredStudent student = registerStudent();
		student.api().get("/api/v1/auth/csrf");
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			List<Callable<Response>> tasks = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				tasks.add(() -> apply(student.api(), BACKEND_JAVA));
			}
			List<Integer> statuses = new ArrayList<>();
			for (Future<Response> future : pool.invokeAll(tasks)) {
				statuses.add(future.get().status());
			}
			assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
			assertThat(statuses).filteredOn(s -> s == 409).hasSize(3);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(count("SELECT count(*) FROM application WHERE user_id = ?", student.id())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM application_stage_event e JOIN application a ON a.id = e.application_id WHERE a.user_id = ?",
				student.id())).as("sin eventos huérfanos de los intentos fallidos").isEqualTo(1);
	}

	@Test
	@DisplayName("008-04: tras retirar se puede postular de nuevo: nueva postulación con otro id y la anterior sigue como WITHDRAWN")
	void t008_04_repostulate() {
		RegisteredStudent student = registerStudent();
		String first = applyOk(student.api(), BACKEND_JAVA);
		assertThat(student.api().post(APPLICATIONS + "/" + first + "/withdraw", null).status()).isEqualTo(200);

		String second = applyOk(student.api(), BACKEND_JAVA);

		assertThat(second).isNotEqualTo(first);
		assertThat(student.api().get(APPLICATIONS + "/" + first).json().get("status").asString()).isEqualTo("WITHDRAWN");
		assertThat(student.api().get(APPLICATIONS + "/" + second).json().get("status").asString()).isEqualTo("SUBMITTED");
	}

	@Test
	@DisplayName("008-05: vacante inexistente o mal formada da el mismo 404; cerrada, 409; sin vacancyId, 422; no se crea nada")
	void t008_05_vacancyErrors() {
		RegisteredStudent student = registerStudent();
		UUID closed = insertVacancyWithJava("Vacante cerrada de prueba", OffsetDateTime.now(ZoneOffset.UTC).minusDays(1),
				"CLOSED", UUID.randomUUID(), true);

		Response missing = apply(student.api(), UUID.randomUUID().toString());
		Response malformed = apply(student.api(), "no-es-un-uuid");
		Response closedResponse = apply(student.api(), closed.toString());
		Response noBody = student.api().post(APPLICATIONS, Map.of());
		Response blank = apply(student.api(), "  ");

		assertThat(missing.status()).isEqualTo(404);
		assertThat(code(missing)).isEqualTo("vacancy-not-found");
		assertThat(malformed.status()).isEqualTo(404);
		assertThat(code(malformed)).isEqualTo("vacancy-not-found");
		assertThat(closedResponse.status()).isEqualTo(409);
		assertThat(code(closedResponse)).isEqualTo("vacancy-closed");
		assertThat(noBody.status()).isEqualTo(422);
		assertThat(noBody.json().get("errors").get(0).get("field").asString()).isEqualTo("vacancyId");
		assertThat(noBody.json().get("errors").get(0).get("rule").asString()).isEqualTo("required");
		assertThat(blank.status()).isEqualTo(422);
		assertThat(count("SELECT count(*) FROM application WHERE user_id = ?", student.id())).isZero();
	}

	// ------------------------------------------------------------------------------------------ 008-06

	@Test
	@DisplayName("008-06: retirar da 200 WITHDRAWN con withdrawnAt, historial SUBMITTED a WITHDRAWN y auditoría")
	void t008_06_withdraw() {
		RegisteredStudent student = registerStudent();
		String id = applyOk(student.api(), BACKEND_JAVA);

		Response response = student.api().post(APPLICATIONS + "/" + id + "/withdraw", null);

		assertThat(response.status()).isEqualTo(200);
		JsonNode application = response.json();
		assertThat(application.get("status").asString()).isEqualTo("WITHDRAWN");
		assertThat(application.get("withdrawnAt").asString()).isNotBlank();
		assertThat(application.get("history")).extracting(h -> h.get("status").asString())
				.containsExactly("SUBMITTED", "WITHDRAWN");
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'APPLICATION_WITHDRAWN' AND subject_id = ?::uuid AND actor_user_id = ?",
				id, student.id())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM application_stage_event WHERE application_id = ?::uuid AND from_status = 'SUBMITTED' AND to_status = 'WITHDRAWN'",
				id)).isEqualTo(1);
	}

	@Test
	@DisplayName("008-06: una retirada no se reabre: retirar de nuevo da 409 application-not-withdrawable")
	void t008_06_withdrawTwice() {
		RegisteredStudent student = registerStudent();
		String id = applyOk(student.api(), BACKEND_JAVA);
		student.api().post(APPLICATIONS + "/" + id + "/withdraw", null);

		Response again = student.api().post(APPLICATIONS + "/" + id + "/withdraw", null);

		assertThat(again.status()).isEqualTo(409);
		assertThat(code(again)).isEqualTo("application-not-withdrawable");
		assertThat(count("SELECT count(*) FROM application_stage_event WHERE application_id = ?::uuid", id)).isEqualTo(2);
	}

	@Test
	@DisplayName("008-06: con dos retiros simultáneos uno responde 200 y el otro 409, con un solo evento y una sola auditoría")
	void t008_06_concurrentWithdraw() throws Exception {
		RegisteredStudent student = registerStudent();
		String id = applyOk(student.api(), BACKEND_JAVA);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Callable<Response>> tasks = List.of(() -> student.api().post(APPLICATIONS + "/" + id + "/withdraw", null),
					() -> student.api().post(APPLICATIONS + "/" + id + "/withdraw", null));
			List<Integer> statuses = new ArrayList<>();
			for (Future<Response> future : pool.invokeAll(tasks)) {
				statuses.add(future.get().status());
			}
			assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'APPLICATION_WITHDRAWN' AND subject_id = ?::uuid",
				id)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM application_stage_event WHERE application_id = ?::uuid AND to_status = 'WITHDRAWN'",
				id)).isEqualTo(1);
	}

	@Test
	@DisplayName("008-06: una vacante que se cerró después no impide retirar (T8) y la postulación se ve con su vacante cerrada")
	void t008_06_withdrawAfterVacancyClosed() {
		RegisteredStudent student = registerStudent();
		UUID vacancy = insertOpenWithJava("Se cerrará pronto");
		String id = applyOk(student.api(), vacancy.toString());
		jdbc.update("UPDATE vacancy SET status = 'CLOSED' WHERE id = ?", vacancy);

		JsonNode detail = student.api().get(APPLICATIONS + "/" + id).json();
		Response withdrawn = student.api().post(APPLICATIONS + "/" + id + "/withdraw", null);

		assertThat(detail.get("status").asString()).isEqualTo("SUBMITTED");
		assertThat(detail.get("vacancy").get("status").asString()).isEqualTo("CLOSED");
		assertThat(withdrawn.status()).isEqualTo(200);
	}

	@Test
	@DisplayName("008-13: un estado de E3 en la base se muestra sin romper y no se puede retirar")
	void t008_13_futureStatesAreNotWithdrawable() {
		RegisteredStudent student = registerStudent();
		String id = applyOk(student.api(), BACKEND_JAVA);
		jdbc.update("UPDATE application SET status = 'UNDER_REVIEW' WHERE id = ?::uuid", id);

		assertThat(student.api().get(APPLICATIONS + "/" + id).json().get("status").asString()).isEqualTo("UNDER_REVIEW");
		Response withdraw = student.api().post(APPLICATIONS + "/" + id + "/withdraw", null);
		assertThat(withdraw.status()).isEqualTo(409);
		assertThat(code(withdraw)).isEqualTo("application-not-withdrawable");
		assertThat(apply(student.api(), BACKEND_JAVA).status()).as("sigue activa").isEqualTo(409);
	}

	// ------------------------------------------------------------------------------------------ 008-07 a 09

	@Test
	@DisplayName("008-07: la lista trae solo lo propio, de la más reciente a la más antigua, con retiradas, paginación y filtro")
	void t008_07_list() {
		RegisteredStudent student = registerStudent();
		RegisteredStudent other = registerStudent();
		List<String> vacancies = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			vacancies.add(insertOpenWithJava("Vacante lista " + i).toString());
		}
		List<String> ids = new ArrayList<>();
		for (String vacancy : vacancies) {
			ids.add(applyOk(student.api(), vacancy));
			clock.advance(java.time.Duration.ofSeconds(2));
		}
		applyOk(other.api(), vacancies.get(0));
		student.api().post(APPLICATIONS + "/" + ids.get(0) + "/withdraw", null);

		JsonNode page = student.api().get(APPLICATIONS).json();

		assertThat(page.get("totalItems").asInt()).isEqualTo(3);
		assertThat(page.get("size").asInt()).isEqualTo(10);
		assertThat(page.get("page").asInt()).isEqualTo(1);
		assertThat(page.get("totalPages").asInt()).isEqualTo(1);
		assertThat(page.get("items")).extracting(i -> i.get("id").asString())
				.containsExactly(ids.get(2), ids.get(1), ids.get(0));
		assertThat(page.get("items").get(2).get("status").asString()).as("incluye las retiradas").isEqualTo("WITHDRAWN");
		assertThat(page.get("items").get(0).get("vacancy").get("title").asString()).isEqualTo("Vacante lista 2");
		assertThat(page.get("items").get(0).get("vacancy").get("company").get("name").asString()).isNotBlank();
		JsonNode paged = student.api().get(APPLICATIONS + "?size=2&page=2").json();
		assertThat(paged.get("items")).extracting(i -> i.get("id").asString()).containsExactly(ids.get(0));
		assertThat(paged.get("totalPages").asInt()).isEqualTo(2);
		JsonNode filtered = student.api().get(APPLICATIONS + "?vacancyId=" + vacancies.get(1)).json();
		assertThat(filtered.get("items")).extracting(i -> i.get("id").asString()).containsExactly(ids.get(1));
		assertThat(filtered.get("totalItems").asInt()).isEqualTo(1);
	}

	@Test
	@DisplayName("008-07: size mayor a 24, página 0 o valores no numéricos dan 422")
	void t008_07_listValidation() {
		ApiClient api = registerStudent().api();

		for (String query : List.of("size=25", "size=0", "page=0", "size=abc", "page=x", "vacancyId=no")) {
			Response response = api.get(APPLICATIONS + "?" + query);

			assertThat(response.status()).as(query).isEqualTo(422);
			assertThat(response.json().get("errors")).isNotEmpty();
		}
		assertThat(api.get(APPLICATIONS + "?size=24").status()).isEqualTo(200);
	}

	@Test
	@DisplayName("008-08: el detalle de lo propio da 200 con historial; ajeno, inexistente o mal formado dan el mismo 404")
	void t008_08_detail() {
		RegisteredStudent owner = registerStudent();
		RegisteredStudent intruder = registerStudent();
		String id = applyOk(owner.api(), BACKEND_JAVA);

		JsonNode detail = owner.api().get(APPLICATIONS + "/" + id).json();

		assertThat(detail.get("id").asString()).isEqualTo(id);
		assertThat(detail.get("history")).hasSize(1);
		for (String candidate : List.of(id, UUID.randomUUID().toString(), "no-es-un-uuid")) {
			Response response = intruder.api().get(APPLICATIONS + "/" + candidate);
			assertThat(response.status()).as(candidate).isEqualTo(404);
			assertThat(code(response)).isEqualTo("application-not-found");
		}
		assertThat(intruder.api().post(APPLICATIONS + "/" + id + "/withdraw", null).status()).isEqualTo(404);
		assertThat(owner.api().get(APPLICATIONS + "/" + id).json().get("status").asString())
				.as("el intruso no pudo retirarla").isEqualTo("SUBMITTED");
	}

	@Test
	@DisplayName("008-09: el resumen cuenta activas y retiradas y trae la más reciente (o nulo)")
	void t008_09_summary() {
		ApiClient api = registerStudent().api();
		JsonNode empty = api.get(APPLICATIONS + "/summary").json();
		assertThat(empty.get("active").asInt()).isZero();
		assertThat(empty.get("withdrawn").asInt()).isZero();
		assertThat(empty.get("latest").isNull()).isTrue();

		String first = applyOk(api, insertOpenWithJava("Resumen uno").toString());
		clock.advance(java.time.Duration.ofSeconds(2));
		applyOk(api, insertOpenWithJava("Resumen dos").toString());
		api.post(APPLICATIONS + "/" + first + "/withdraw", null);

		JsonNode summary = api.get(APPLICATIONS + "/summary").json();
		assertThat(summary.get("active").asInt()).isEqualTo(1);
		assertThat(summary.get("withdrawn").asInt()).isEqualTo(1);
		assertThat(summary.get("latest").get("vacancy").get("title").asString()).isEqualTo("Resumen dos");
	}

	// ------------------------------------------------------------------------------------------ 008-10

	@Test
	@DisplayName("008-10: sin sesión las cinco rutas dan 401, sin CSRF las escrituras 403, y todo es no-store")
	void t008_10_sessionAndCsrf() {
		String id = UUID.randomUUID().toString();
		for (Response response : List.of(client().get(APPLICATIONS), client().get(APPLICATIONS + "/summary"),
				client().get(APPLICATIONS + "/" + id), client().post(APPLICATIONS, Map.of("vacancyId", BACKEND_JAVA)),
				client().post(APPLICATIONS + "/" + id + "/withdraw", null))) {
			assertThat(response.status()).isEqualTo(401);
			assertThat(response.header("Cache-Control")).contains("no-store");
		}
		RegisteredStudent student = registerStudent();
		String own = applyOk(student.api(), BACKEND_JAVA);
		assertThat(student.api().writeWithToken("POST", APPLICATIONS, Map.of("vacancyId", insertOpenWithJava("Sin csrf").toString()), null)
				.status()).isEqualTo(403);
		assertThat(student.api().writeWithToken("POST", APPLICATIONS + "/" + own + "/withdraw", null, "falso").json()
				.get("code").asString()).isEqualTo("csrf-invalid");
		assertThat(student.api().get(APPLICATIONS + "/" + own).json().get("status").asString()).isEqualTo("SUBMITTED");
		for (Response response : List.of(student.api().get(APPLICATIONS), student.api().get(APPLICATIONS + "/summary"),
				student.api().get(APPLICATIONS + "/" + own), student.api().get(APPLICATIONS + "/" + id),
				apply(student.api(), "no-es-un-uuid"))) {
			assertThat(response.header("Cache-Control")).contains("no-store");
		}
	}

	@Test
	@DisplayName("008-10: con rol COMPANY o ADMIN todas las rutas dan 403 access-denied y no crean nada")
	void t008_10_otherRolesAreForbidden() {
		String id = UUID.randomUUID().toString();
		for (Role role : List.of(Role.COMPANY, Role.ADMIN)) {
			CreatedUser user = createUser(role);
			ApiClient api = client();
			assertThat(api.post("/api/v1/auth/login", loginBody(user.email(), user.password())).status()).isEqualTo(200);

			for (Response response : List.of(api.get(APPLICATIONS), api.get(APPLICATIONS + "/summary"),
					api.get(APPLICATIONS + "/" + id), apply(api, BACKEND_JAVA),
					api.post(APPLICATIONS + "/" + id + "/withdraw", null))) {
				assertThat(response.status()).as(role.name()).isEqualTo(403);
				assertThat(code(response)).isEqualTo("access-denied");
				assertThat(response.header("Cache-Control")).contains("no-store");
			}
			assertThat(count("SELECT count(*) FROM application WHERE user_id = ?", user.id())).isZero();
		}
	}

	@Test
	@DisplayName("008-10: dos estudiantes nunca ven las postulaciones del otro")
	void t008_10_isolation() {
		ApiClient ana = registerStudent().api();
		ApiClient beto = registerStudent().api();
		applyOk(ana, BACKEND_JAVA);

		assertThat(beto.get(APPLICATIONS).json().get("totalItems").asInt()).isZero();
		assertThat(beto.get(APPLICATIONS + "/summary").json().get("active").asInt()).isZero();
		assertThat(ana.get(APPLICATIONS).json().get("totalItems").asInt()).isEqualTo(1);
		// Beto puede postular a la misma vacante: la unicidad es por persona.
		assertThat(apply(beto, BACKEND_JAVA).status()).isEqualTo(201);
	}

	@Test
	@DisplayName("008-12: la respuesta no incluye datos de sesiones de práctica ni del perfil")
	void t008_12_noPracticeOrProfileData() {
		RegisteredStudent student = registerStudent();
		fillEverything(student.api());
		String body = apply(student.api(), BACKEND_JAVA).body();

		assertThat(body).doesNotContain("practice").doesNotContain("Desarrollo de Software").doesNotContain("Tienda web")
				.doesNotContain(student.email());
		assertThat(count("SELECT count(*) FROM information_schema.columns WHERE table_name = 'application' AND column_name IN ('profile', 'snapshot', 'practice_session_id')"))
				.as("no hay copia del perfil").isZero();
	}

}
