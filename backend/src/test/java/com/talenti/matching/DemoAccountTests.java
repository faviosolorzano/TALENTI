package com.talenti.matching;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/**
 * T-10: con la semilla de ejemplo activa existe una cuenta de demostración de estudiante con el perfil lleno, que
 * entra por la API real con la contraseña documentada en el README y muestra una compatibilidad alta.
 */
class DemoAccountTests extends MatchingIntegrationTest {

	static final String EMAIL = "demo@example.test";

	/** La contraseña de demostración documentada en README.md (no es un secreto). */
	static final String PASSWORD = "Demo-Talenti-2026";

	private ApiClient login() {
		ApiClient api = client();
		Response response = api.post("/api/v1/auth/login", loginBody(EMAIL, PASSWORD));
		assertThat(response.status()).as(response.body()).isEqualTo(200);
		return api;
	}

	@Test
	@DisplayName("T-10: la cuenta de demostración inicia sesión con la contraseña documentada y una incorrecta no entra")
	void t10_documentedPasswordWorks() {
		assertThat(client().post("/api/v1/auth/login", loginBody(EMAIL, "otra-clave-1")).status()).isEqualTo(401);
		ApiClient api = login();

		JsonNode me = api.get("/api/v1/auth/me").json();
		assertThat(me.get("role").asString()).isEqualTo("STUDENT");
		assertThat(me.get("legalAcceptancePending").asBoolean()).as("no cae en /aceptar-documentos").isFalse();
	}

	@Test
	@DisplayName("T-10: el perfil de la cuenta de demostración está lleno y coherente, con avance alto")
	void t10_profileIsFull() {
		JsonNode profile = ok(login().get(PROFILE));

		assertThat(profile.get("completeness").get("percent").asInt()).isEqualTo(100);
		assertThat(profile.get("academic").get("career").asString()).isEqualTo("Desarrollo de Software");
		assertThat(profile.get("academic").get("cycle").asInt()).isEqualTo(5);
		assertThat(profile.get("skills")).extracting(s -> s.get("slug").asString()).contains("java", "sql", "git",
				"spring-boot");
		assertThat(profile.get("projects")).hasSize(2);
		assertThat(profile.get("availability").get("hoursPerWeek").asInt()).isEqualTo(20);
		assertThat(profile.get("availability").get("location").asString()).isEqualTo("Lima");
	}

	@Test
	@DisplayName("T-10: la cuenta de demostración tiene una compatibilidad alta con 'Practicante Backend Java'")
	void t10_highCompatibilityWithTheJavaVacancy() {
		JsonNode detail = detail(login(), BACKEND_JAVA);

		assertThat(detail.get("percent").asInt()).isGreaterThanOrEqualTo(85);
	}

	@Test
	@DisplayName("T-10: cargar la semilla otra vez no duplica la cuenta (idempotente)")
	void t10_isIdempotent() {
		com.talenti.vacancy.config.DemoDataMigrator.flyway(jdbc.getDataSource()).migrate();

		assertThat(count("SELECT count(*) FROM app_user WHERE email = ?", EMAIL)).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM student_project p JOIN app_user u ON u.id = p.user_id WHERE u.email = ?",
				EMAIL)).isEqualTo(2);
	}

}
