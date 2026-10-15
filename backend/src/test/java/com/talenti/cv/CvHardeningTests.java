package com.talenti.cv;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.talenti.cv.service.CvReadService;
import com.talenti.cv.service.ReadResult;
import com.talenti.shared.ai.AiLevel;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

/**
 * Endurecimiento del lector de CV: los intentos fallidos también cuentan (009-07) y el cupo de IA se reserva de
 * verdad aunque varias lecturas lleguen a la vez (009-28). La IA es un adaptador SIMULADO.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"talenti.ai.cv.enabled=true", "talenti.ai.cv.model=modelo-simulado", "talenti.ai.cv.timeout=5s" })
@Import(CountingAiAdapter.Configuration.class)
class CvHardeningTests extends CvIntegrationTest {

	@Autowired
	private CountingAiAdapter ai;

	@Autowired
	private CvReadService reads;

	@BeforeEach
	void resetAdapter() {
		ai.reset();
	}

	@Test
	@DisplayName("009-07: los PDF inválidos también cuentan: tras 20 intentos fallidos en 24 horas, el siguiente da 429 cv-limit-reached con Retry-After")
	void t009_07_failedUploadsCount() {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		byte[] notAPdf = "Esto no es un PDF, es texto plano inventado.".getBytes(java.nio.charset.StandardCharsets.UTF_8);

		for (int attempt = 1; attempt <= 20; attempt++) {
			// El límite por origen (10 por minuto) es otro; se cambia de origen para probar solo el de la persona.
			api.origin("10.77." + (attempt / 5) + "." + attempt);
			Response response = upload(api, notAPdf);
			assertThat(response.status()).as("intento %d", attempt).isEqualTo(422);
			assertThat(response.json().get("code").asString()).isEqualTo("cv-unreadable");
		}
		api.origin("10.78.0.1");
		Response blocked = upload(api, notAPdf);

		assertThat(blocked.status()).isEqualTo(429);
		assertThat(blocked.json().get("code").asString()).isEqualTo("cv-limit-reached");
		assertThat(Long.parseLong(blocked.header("Retry-After"))).isPositive();
		assertThat(analysisRows(student.id())).as("ningún intento fallido crea análisis").isZero();
		// Otra persona no se ve afectada.
		ApiClient other = registerStudent().api();
		assertThat(upload(other, notAPdf).status()).isEqualTo(422);
	}

	@Test
	@DisplayName("009-28: cinco lecturas con IA simultáneas de la misma persona no superan el cupo de 3 al mes ni llaman más de 3 veces a la IA")
	void t009_28_concurrentReadsRespectTheQuota() throws Exception {
		RegisteredStudent student = registerStudent();
		ai.delayMillis = 300;
		String text = "Programador con experiencia en Java, Spring Boot y SQL. Proyecto Tienda en línea con Java y SQL.";

		ExecutorService pool = Executors.newFixedThreadPool(5);
		List<ReadResult> results = new ArrayList<>();
		try {
			List<Callable<ReadResult>> tasks = new ArrayList<>();
			for (int i = 0; i < 5; i++) {
				tasks.add(() -> reads.execute(student.id(), UUID.randomUUID(), AiLevel.ENHANCED, text, false));
			}
			for (Future<ReadResult> future : pool.invokeAll(tasks)) {
				results.add(future.get());
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(ai.calls.get()).as("llamadas a la IA").isEqualTo(3);
		assertThat(usageRows(student.id(), "SUCCESS")).isEqualTo(3);
		assertThat(results).filteredOn(result -> result.level() == AiLevel.ENHANCED).hasSize(3);
		assertThat(results).filteredOn(result -> result.level() == AiLevel.BASIC)
				.allSatisfy(result -> assertThat(result.degraded()).isEqualTo("PERSONAL_LIMIT"));
		assertThat(usageRows(student.id(), "BLOCKED_PERSONAL_QUOTA")).isEqualTo(2);
	}

}
