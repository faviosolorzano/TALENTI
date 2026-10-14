package com.talenti.cv;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;

import com.talenti.profile.ProfileIntegrationTest;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;
import com.talenti.support.SampleCv;

import tools.jackson.databind.JsonNode;

/**
 * Base de las pruebas de integración del lector de CV (spec 009): aplicación completa con servidor HTTP real y
 * PostgreSQL real. Todos los CV son inventados y se generan en la prueba.
 */
public abstract class CvIntegrationTest extends ProfileIntegrationTest {

	protected static final String CV = "/api/v1/cv";

	/** Los relojes que una prueba adelantó vuelven a la hora real, para no afectar a las demás. */
	@AfterEach
	void restoreClock() {
		clock.set(Instant.now());
	}

	protected Response upload(ApiClient api, byte[] pdf) {
		return upload(api, pdf, null);
	}

	protected Response upload(ApiClient api, byte[] pdf, String mode) {
		return api.postMultipart(CV + "/analyses", "mi-cv-secreto.pdf", "application/pdf", pdf,
				mode == null ? Map.of() : Map.of("mode", mode));
	}

	/** Sube el CV de ejemplo y espera a que termine; devuelve el análisis. */
	protected JsonNode uploadSampleAndWait(ApiClient api) {
		Response response = upload(api, SampleCv.pdf(SampleCv.uniqueMark()));
		assertThat(response.status()).as("subida: %s", response.body()).isEqualTo(202);
		return awaitDone(api);
	}

	/** Espera a que el análisis vigente esté READY o FAILED (hasta 20 s). */
	protected JsonNode awaitDone(ApiClient api) {
		long deadline = System.currentTimeMillis() + 20_000;
		JsonNode last = null;
		while (System.currentTimeMillis() < deadline) {
			Response response = api.get(CV + "/analyses/current");
			if (response.status() == 200) {
				last = response.json();
				String status = last.get("status").asString();
				if (status.equals("READY") || status.equals("FAILED")) {
					return last;
				}
			}
			try {
				Thread.sleep(40);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
		}
		throw new AssertionError("El análisis no terminó a tiempo: " + (last == null ? "sin análisis" : last));
	}

	protected static List<JsonNode> suggestions(JsonNode analysis, String kind) {
		List<JsonNode> result = new ArrayList<>();
		for (JsonNode suggestion : analysis.get("suggestions")) {
			if (suggestion.get("kind").asString().equals(kind)) {
				result.add(suggestion);
			}
		}
		return result;
	}

	protected static JsonNode skillSuggestion(JsonNode analysis, String slug) {
		return suggestions(analysis, "SKILL").stream()
				.filter(s -> !s.get("value").get("slug").isNull() && s.get("value").get("slug").asString().equals(slug))
				.findFirst().orElseThrow(() -> new AssertionError("Sin sugerencia de " + slug));
	}

	protected static JsonNode academicSuggestion(JsonNode analysis, String field) {
		return suggestions(analysis, "ACADEMIC").stream().filter(s -> s.get("field").asString().equals(field))
				.findFirst().orElseThrow(() -> new AssertionError("Sin sugerencia de " + field));
	}

	protected static Map<String, Object> decision(JsonNode suggestion, String action, Map<String, Object> value) {
		return value == null ? body("suggestionId", suggestion.get("id").asString(), "action", action)
				: body("suggestionId", suggestion.get("id").asString(), "action", action, "value", value);
	}

	protected static Map<String, Object> confirmBody(List<Map<String, Object>> decisions) {
		return body("decisions", decisions);
	}

	protected int analysisRows(UUID userId) {
		return count("SELECT count(*) FROM cv_analysis WHERE user_id = ?", userId);
	}

	protected int usageRows(UUID userId) {
		return count("SELECT count(*) FROM ai_usage_event WHERE user_id = ?", userId);
	}

	protected int usageRows(UUID userId, String outcome) {
		return count("SELECT count(*) FROM ai_usage_event WHERE user_id = ? AND outcome = ?", userId, outcome);
	}

}
