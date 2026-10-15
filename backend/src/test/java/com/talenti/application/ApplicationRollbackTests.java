package com.talenti.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import com.talenti.matching.MatchingIntegrationTest;
import com.talenti.shared.audit.AuditEvent;
import com.talenti.shared.audit.AuditRecorder;
import com.talenti.support.ApiClient.Response;

/** 008-02 y 008-06: fila, historial y auditoría van en UNA transacción; si la auditoría falla, no queda nada. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "talenti.demo-data.enabled=true")
@Import(ApplicationRollbackTests.FailingAudit.class)
class ApplicationRollbackTests extends MatchingIntegrationTest {

	/** Las postulaciones y las vacantes de prueba no deben quedar: otras pruebas comparten la misma base. */
	@org.junit.jupiter.api.AfterEach
	void cleanUp() {
		jdbc.update("DELETE FROM application");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FailingAudit {

		@Bean
		@Primary
		AuditRecorder failingAuditRecorder() {
			return new AuditRecorder() {

				@Override
				public void record(AuditEvent event) {
					if (event.type().startsWith("APPLICATION_")) {
						throw new IllegalStateException("falla simulada de la auditoría");
					}
				}

			};
		}

	}

	@Test
	@DisplayName("008-02: si falla la auditoría al postular, no queda ni la postulación ni su evento")
	void t008_02_submitRollsBack() {
		RegisteredStudent student = registerStudent();

		Response response = student.api().post("/api/v1/applications", Map.of("vacancyId", BACKEND_JAVA));

		assertThat(response.status()).isEqualTo(500);
		assertThat(count("SELECT count(*) FROM application WHERE user_id = ?", student.id())).isZero();
		assertThat(count("SELECT count(*) FROM application_stage_event e JOIN application a ON a.id = e.application_id WHERE a.user_id = ?",
				student.id())).isZero();
		assertThat(student.api().get("/api/v1/applications").json().get("totalItems").asInt()).isZero();
	}

	@Test
	@DisplayName("008-06: si falla la auditoría al retirar, la postulación sigue enviada y sin evento de retiro")
	void t008_06_withdrawRollsBack() {
		RegisteredStudent student = registerStudent();
		java.util.UUID id = java.util.UUID.randomUUID();
		jdbc.update("INSERT INTO application (id, user_id, vacancy_id, status, submitted_at) VALUES (?, ?, ?::uuid, 'SUBMITTED', now())",
				id, student.id(), BACKEND_JAVA);
		jdbc.update("INSERT INTO application_stage_event (application_id, from_status, to_status, occurred_at) VALUES (?, NULL, 'SUBMITTED', now())",
				id);

		Response response = student.api().post("/api/v1/applications/" + id + "/withdraw", null);

		assertThat(response.status()).isEqualTo(500);
		assertThat(jdbc.queryForObject("SELECT status FROM application WHERE id = ?", String.class, id))
				.isEqualTo("SUBMITTED");
		assertThat(count("SELECT count(*) FROM application_stage_event WHERE application_id = ?", id)).isEqualTo(1);
	}

}
