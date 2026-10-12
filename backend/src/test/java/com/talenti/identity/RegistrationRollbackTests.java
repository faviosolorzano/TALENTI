package com.talenti.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.talenti.identity.service.ConsentService;
import com.talenti.shared.audit.AuditEvent;
import com.talenti.shared.audit.AuditRecorder;
import com.talenti.support.ApiClient.Response;

/**
 * 001-06: el registro, los permisos y la auditoría son una sola transacción. Se fuerza un fallo en cada paso
 * posterior a la creación del usuario y se comprueba que no queda nada en ninguna de las tres tablas.
 */
class RegistrationRollbackTests extends IdentityIntegrationTest {

	@MockitoSpyBean
	private AuditRecorder audit;

	@MockitoSpyBean
	private ConsentService consents;

	@Test
	@DisplayName("001-06: si falla la auditoría (después de guardar usuario, permisos y el primer evento) no queda ningún usuario")
	void t001_06_auditFailureRollsEverythingBack() {
		// El primer evento (USER_REGISTERED o el primer CONSENT_ACCEPTED) sí se escribe; el segundo falla.
		doCallRealMethod().doThrow(new IllegalStateException("fallo simulado de auditoría"))
				.when(audit).record(any(AuditEvent.class));
		TableCounts before = TableCounts.of(this);
		String email = uniqueEmail();

		Response response = client().post("/api/v1/auth/register", registerBody(email, validPassword()));

		assertThat(response.status()).isEqualTo(500);
		assertThat(response.json().get("code").asString()).isEqualTo("internal-error");
		assertThat(response.body()).doesNotContain("fallo simulado").doesNotContain("IllegalStateException");
		assertThat(usersWithEmail(email)).isZero();
		assertThat(TableCounts.of(this)).isEqualTo(before);
	}

	@Test
	@DisplayName("001-06: si falla el guardado del consentimiento (con el usuario ya insertado) no queda ningún usuario")
	void t001_06_consentFailureRollsEverythingBack() {
		doThrow(new IllegalStateException("fallo simulado de consentimiento"))
				.when(consents).acceptAccountDocuments(any(UUID.class), anyString());
		TableCounts before = TableCounts.of(this);
		String email = uniqueEmail();

		Response response = client().post("/api/v1/auth/register", registerBody(email, validPassword()));

		assertThat(response.status()).isEqualTo(500);
		assertThat(usersWithEmail(email)).isZero();
		assertThat(TableCounts.of(this)).isEqualTo(before);
	}

	private record TableCounts(int users, int consents, int auditEvents) {

		static TableCounts of(IdentityIntegrationTest test) {
			return new TableCounts(test.count("SELECT count(*) FROM app_user"),
					test.count("SELECT count(*) FROM consent_record"), test.count("SELECT count(*) FROM audit_event"));
		}

	}

}
