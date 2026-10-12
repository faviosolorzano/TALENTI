package com.talenti.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/** Documentos legales vigentes y aceptación de una versión nueva: criterios 001-17, 001-18 y 001-28 a 001-30. */
class LegalIntegrationTests extends IdentityIntegrationTest {

	private static final String CURRENT = "/api/v1/legal/current";

	private static final String ACCEPT = "/api/v1/legal/accept";

	private static final String ME = "/api/v1/auth/me";

	@Test
	@DisplayName("001-17: cualquier visitante recibe la versión y los cuatro documentos, y es la misma versión que se guarda al registrarse")
	void t001_17_currentDocumentsArePublicAndMatchTheRegisteredVersion() {
		ApiClient visitor = client();

		Response response = visitor.get(CURRENT);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.setCookies()).isEmpty();
		JsonNode body = response.json();
		String version = body.get("version").asString();
		assertThat(version).isEqualTo("v1");
		List<String> keys = new ArrayList<>();
		for (JsonNode document : body.get("documents")) {
			keys.add(document.get("key").asString());
			assertThat(document.get("title").asString()).isNotBlank();
			assertThat(document.get("sections").size()).isPositive();
			for (JsonNode section : document.get("sections")) {
				assertThat(section.get("body").asString()).isNotBlank();
			}
		}
		assertThat(keys).containsExactly("terms", "privacy", "cookies", "consent-account");
		// El comentario HTML del borrador (instrucciones internas) no llega al usuario.
		assertThat(response.body()).doesNotContain("<!--").doesNotContain("Este comentario no se muestra");

		RegisteredStudent student = registerStudent();
		assertThat(jdbc.queryForList("SELECT DISTINCT consent_version FROM consent_record WHERE user_id = ?",
				String.class, student.id())).containsExactly(version);
	}

	@Test
	@DisplayName("001-17: los textos salen de los archivos versionados con título y secciones por encabezado")
	void t001_17_documentsComeFromTheVersionedFiles() {
		JsonNode documents = client().get(CURRENT).json().get("documents");

		assertThat(documents.get(0).get("title").asString()).isEqualTo("Términos y Condiciones de Talenti");
		assertThat(headings(documents.get(0))).contains("1. Quiénes somos y qué aceptas", "17. Contacto");
		assertThat(headings(documents.get(1))).contains("1. Quién es el responsable");
		assertThat(headings(documents.get(2))).contains("Qué cookies usa Talenti");
		assertThat(documents.get(2).get("sections").toString()).contains("XSRF-TOKEN");
	}

	@Test
	@DisplayName("001-18: el consentimiento de datos de la cuenta tiene las cuatro secciones obligatorias")
	void t001_18_accountConsentHasTheFourRequiredSections() {
		JsonNode consent = null;
		for (JsonNode document : client().get(CURRENT).json().get("documents")) {
			if (document.get("key").asString().equals("consent-account")) {
				consent = document;
			}
		}

		assertThat(consent).isNotNull();
		assertThat(headings(consent)).contains(
				"Qué datos recogemos",
				"Para qué los usamos",
				"Qué enviamos a proveedores externos de IA y voz",
				"Cuánto tiempo conservamos tus datos");
		assertThat(consent.toString()).contains("proveedores externos").contains("fuera del Perú");
	}

	@Test
	@DisplayName("001-28: GET /legal/current incluye publishedAt como fecha ISO de publicación")
	void t001_28_currentDocumentsIncludePublishedAt() {
		String publishedAt = client().get(CURRENT).json().get("publishedAt").asString();

		assertThat(Instant.parse(publishedAt)).isEqualTo(Instant.parse("2026-10-11T00:00:00Z"));
	}

	@Test
	@DisplayName("001-29: /auth/me informa legalAcceptancePending cuando la última versión aceptada no es la vigente")
	void t001_29_meReportsPendingAcceptance() {
		RegisteredStudent student = registerStudent();
		assertThat(student.api().get(ME).json().get("legalAcceptancePending").asBoolean()).isFalse();

		// Una versión anterior aceptada más recientemente que la vigente deja la aceptación pendiente.
		insertConsent(student.id(), "TERMS", "v0", clock.instant().plusSeconds(60));
		assertThat(student.api().get(ME).json().get("legalAcceptancePending").asBoolean()).isTrue();

		insertConsent(student.id(), "ACCOUNT_DATA", "v0", clock.instant().plusSeconds(60));
		assertThat(student.api().get(ME).json().get("legalAcceptancePending").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("001-29: un usuario sin permisos registrados (por ejemplo COMPANY o ADMIN) tiene la aceptación pendiente")
	void t001_29_userWithoutConsentsIsPending() {
		CreatedUser user = createUser(Role.COMPANY);
		ApiClient api = client();
		api.post("/api/v1/auth/login", loginBody(user.email(), user.password()));

		assertThat(api.get(ME).json().get("legalAcceptancePending").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("001-30: aceptar la versión vigente inserta TERMS y ACCOUNT_DATA, audita CONSENT_ACCEPTED y responde 204")
	void t001_30_acceptingTheCurrentVersionInsertsConsentsAndAudits() {
		RegisteredStudent student = registerStudent();
		insertConsent(student.id(), "TERMS", "v0", clock.instant().plusSeconds(60));
		insertConsent(student.id(), "ACCOUNT_DATA", "v0", clock.instant().plusSeconds(60));
		assertThat(student.api().get(ME).json().get("legalAcceptancePending").asBoolean()).isTrue();
		clock.advance(java.time.Duration.ofMinutes(5));

		Response response = student.api().post(ACCEPT, Map.of("legalAccepted", true, "legalVersion", "v1"));

		assertThat(response.status()).isEqualTo(204);
		assertThat(response.body()).isEmpty();
		assertThat(count("SELECT count(*) FROM consent_record WHERE user_id = ? AND consent_version = 'v1'",
				student.id())).isEqualTo(4);
		assertThat(count("SELECT count(*) FROM audit_event WHERE actor_user_id = ? AND event_type = 'CONSENT_ACCEPTED'",
				student.id())).isEqualTo(4);
		assertThat(student.api().get(ME).json().get("legalAcceptancePending").asBoolean()).isFalse();
	}

	@Test
	@DisplayName("001-30: aceptar la versión vigente ya aceptada es idempotente: 204 sin insertar ni auditar de nuevo")
	void t001_30_acceptingAnAlreadyAcceptedVersionIsIdempotent() {
		RegisteredStudent student = registerStudent();
		int consents = count("SELECT count(*) FROM consent_record WHERE user_id = ?", student.id());
		int audits = count("SELECT count(*) FROM audit_event WHERE actor_user_id = ?", student.id());
		assertThat(consents).isEqualTo(2);

		for (int i = 0; i < 2; i++) {
			Response response = student.api().post(ACCEPT, Map.of("legalAccepted", true, "legalVersion", "v1"));
			assertThat(response.status()).isEqualTo(204);
		}

		assertThat(count("SELECT count(*) FROM consent_record WHERE user_id = ?", student.id())).isEqualTo(consents);
		assertThat(count("SELECT count(*) FROM audit_event WHERE actor_user_id = ?", student.id())).isEqualTo(audits);
		// Con la casilla sin marcar o con una versión vencida sigue rechazando, aunque ya esté aceptada.
		assertThat(student.api().post(ACCEPT, Map.of("legalAccepted", false, "legalVersion", "v1")).status())
				.isEqualTo(422);
		assertThat(student.api().post(ACCEPT, Map.of("legalAccepted", true, "legalVersion", "v0")).status())
				.isEqualTo(409);
	}

	@Test
	@DisplayName("001-30: sin aceptar responde 422; con una versión vencida, 409; sin sesión, 401; sin token CSRF, 403")
	void t001_30_rejectionsOfAccept() {
		RegisteredStudent student = registerStudent();
		int consentsBefore = count("SELECT count(*) FROM consent_record WHERE user_id = ?", student.id());

		Response notAccepted = student.api().post(ACCEPT, Map.of("legalAccepted", false, "legalVersion", "v1"));
		assertThat(notAccepted.status()).isEqualTo(422);
		assertThat(notAccepted.json().get("errors").get(0).get("rule").asString()).isEqualTo("must-be-accepted");
		Response outdated = student.api().post(ACCEPT, Map.of("legalAccepted", true, "legalVersion", "v0"));
		assertThat(outdated.status()).isEqualTo(409);
		assertThat(outdated.json().get("code").asString()).isEqualTo("legal-version-outdated");
		Response anonymous = client().post(ACCEPT, Map.of("legalAccepted", true, "legalVersion", "v1"));
		assertThat(anonymous.status()).isEqualTo(401);
		Response noCsrf = student.api().postWithoutCsrf(ACCEPT, Map.of("legalAccepted", true, "legalVersion", "v1"));
		assertThat(noCsrf.status()).isEqualTo(403);

		assertThat(count("SELECT count(*) FROM consent_record WHERE user_id = ?", student.id()))
				.isEqualTo(consentsBefore);
	}

	@Test
	@DisplayName("001-30: aceptar no bloquea el resto de la API (el control es de la interfaz)")
	void t001_30_pendingAcceptanceDoesNotBlockTheApi() {
		RegisteredStudent student = registerStudent();
		insertConsent(student.id(), "TERMS", "v0", clock.instant().plusSeconds(60));

		assertThat(student.api().get(ME).status()).isEqualTo(200);
		assertThat(student.api().get(CURRENT).status()).isEqualTo(200);
	}

	private void insertConsent(UUID userId, String purpose, String version, Instant acceptedAt) {
		jdbc.update("INSERT INTO consent_record (user_id, purpose, consent_version, accepted_at) VALUES (?, ?, ?, ?)",
				userId, purpose, version, OffsetDateTime.ofInstant(acceptedAt, ZoneOffset.UTC));
	}

	private static List<String> headings(JsonNode document) {
		List<String> headings = new ArrayList<>();
		for (JsonNode section : document.get("sections")) {
			headings.add(section.get("heading").asString());
		}
		return headings;
	}

}
