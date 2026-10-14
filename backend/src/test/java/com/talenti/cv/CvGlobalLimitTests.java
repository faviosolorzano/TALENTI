package com.talenti.cv;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.talenti.support.ApiClient.Response;
import com.talenti.support.SampleCv;

import tools.jackson.databind.JsonNode;

/**
 * 009-29: con el tope global agotado (aquí el tope diario es 0, así que ya está agotado desde el primer momento), la
 * lectura mejorada se marca no disponible, y una petición mejorada que llegue igual se atiende con la lectura básica,
 * marcada como degradada, sin error ni pérdida del CV y sin llamar a la IA.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"talenti.ai.cv.enabled=true", "talenti.ai.cv.model=modelo-simulado", "talenti.ai.limits.global-daily=0" })
@Import(CountingAiAdapter.Configuration.class)
class CvGlobalLimitTests extends CvIntegrationTest {

	@Autowired
	private CountingAiAdapter ai;

	@BeforeEach
	void resetAdapter() {
		ai.reset();
	}

	@Test
	@DisplayName("009-29: agotado el tope global, /cv/usage marca la mejorada no disponible (GLOBAL_LIMIT) y la básica sigue")
	void t009_29_usageShowsGlobalLimit() {
		RegisteredStudent student = registerStudent();

		JsonNode usage = student.api().get(CV + "/usage").json();

		assertThat(usage.get("enhanced").get("available").asBoolean()).isFalse();
		assertThat(usage.get("enhanced").get("reason").asString()).isEqualTo("GLOBAL_LIMIT");
		assertThat(usage.get("basic").get("available").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("009-29: una petición mejorada que llega igual se atiende con la básica, degraded, sin llamar a la IA y anotando el bloqueo")
	void t009_29_enhancedRequestDegradesToBasic() {
		RegisteredStudent student = registerStudent();

		Response response = upload(student.api(), SampleCv.pdf("MARCAGLOBAL"), "ENHANCED");

		assertThat(response.status()).isEqualTo(202);
		assertThat(response.json().get("degraded").asBoolean()).isTrue();
		assertThat(response.json().get("degradedReason").asString()).isEqualTo("GLOBAL_LIMIT");
		JsonNode analysis = awaitDone(student.api());
		assertThat(analysis.get("status").asString()).isEqualTo("READY");
		assertThat(analysis.get("source").asString()).isEqualTo("RULES");
		assertThat(analysis.get("degraded").asBoolean()).isTrue();
		assertThat(analysis.get("suggestions")).isNotEmpty();
		assertThat(ai.calls.get()).isZero();
		assertThat(usageRows(student.id(), "BLOCKED_GLOBAL_QUOTA")).isEqualTo(1);
		assertThat(usageRows(student.id(), "SUCCESS")).isZero();

		// También al pedir "Leer mejor con IA" sobre el análisis abierto.
		Response again = student.api().post(CV + "/analyses/" + analysis.get("id").asString() + "/reads",
				java.util.Map.of("mode", "ENHANCED"));
		assertThat(again.status()).isEqualTo(202);
		assertThat(again.json().get("degradedReason").asString()).isEqualTo("GLOBAL_LIMIT");
		assertThat(awaitDone(student.api()).get("degraded").asBoolean()).isTrue();
		assertThat(ai.calls.get()).isZero();
	}

}
