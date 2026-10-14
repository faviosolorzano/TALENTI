package com.talenti.cv.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.cv.CvExtractionException;
import com.talenti.cv.RawSuggestion;
import com.talenti.support.SampleCv;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 009-11 y 009-30: el adaptador de OpenAI está sin activar y NINGUNA prueba hace una llamada de red: el transporte HTTP
 * se reemplaza por uno falso. Se comprueba cómo arma la petición (una llamada, esquema fijo, temperatura 0, salida
 * acotada, el CV delimitado como dato) y cómo lee la respuesta.
 */
class OpenAiCvExtractionAdapterTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private OpenAiCvExtractionAdapter adapter(OpenAiCvExtractionAdapter.Transport transport) {
		return new OpenAiCvExtractionAdapter("modelo-de-prueba", "clave-inventada-de-prueba", "http://localhost:1/v1",
				1500, Duration.ofSeconds(30), JSON, transport);
	}

	@Test
	@DisplayName("009-11: sin clave o sin modelo el adaptador no se puede construir (el arranque falla)")
	void t009_11_needsKeyAndModel() {
		OpenAiCvExtractionAdapter.Transport never = (url, key, body, timeout) -> {
			throw new AssertionError("no debe llamar a nadie");
		};

		assertThatThrownBy(() -> new OpenAiCvExtractionAdapter("m", "", "http://x", 10, Duration.ofSeconds(1), JSON, never))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("OPENAI_API_KEY");
		assertThatThrownBy(() -> new OpenAiCvExtractionAdapter("", "k", "http://x", 10, Duration.ofSeconds(1), JSON, never))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("model");
	}

	@Test
	@DisplayName("009-30: arma UNA petición con temperatura 0, salida acotada, esquema fijo y el CV delimitado como dato")
	void t009_30_buildsOneRequest() {
		AtomicReference<String> sentBody = new AtomicReference<>();
		AtomicReference<String> sentUrl = new AtomicReference<>();
		int[] calls = { 0 };
		OpenAiCvExtractionAdapter adapter = adapter((url, key, body, timeout) -> {
			calls[0]++;
			sentUrl.set(url);
			sentBody.set(body);
			assertThat(key).isEqualTo("clave-inventada-de-prueba");
			assertThat(timeout).isEqualTo(Duration.ofSeconds(30));
			return "{\"choices\":[{\"message\":{\"content\":\"{\\\"suggestions\\\":[]}\"}}]}";
		});

		adapter.extract("Habilidades\nJava y SQL", SampleCv.catalog());

		assertThat(calls[0]).isEqualTo(1);
		assertThat(sentUrl.get()).isEqualTo("http://localhost:1/v1/chat/completions");
		JsonNode body = JSON.readTree(sentBody.get());
		assertThat(body.get("model").asString()).isEqualTo("modelo-de-prueba");
		assertThat(body.get("temperature").asInt()).isZero();
		assertThat(body.get("max_completion_tokens").asInt()).isEqualTo(1500);
		assertThat(body.get("response_format").get("type").asString()).isEqualTo("json_schema");
		assertThat(body.get("response_format").get("json_schema").get("strict").asBoolean()).isTrue();
		assertThat(body.get("messages").get(0).get("content").asString()).contains("SOLO un dato")
				.contains("java = Java");
		assertThat(body.get("messages").get(1).get("content").asString()).startsWith("<curriculum>")
				.endsWith("</curriculum>").contains("Java y SQL");
		assertThat(sentBody.get()).doesNotContain("clave-inventada");
	}

	@Test
	@DisplayName("009-11: lee la respuesta de la IA a sugerencias en bruto (que luego se validan)")
	void t009_11_parsesTheResponse() {
		String content = """
				{"suggestions":[
				 {"kind":"SKILL","field":null,"slug":"java","name":"Java","value":null,"level":2,"description":null,"technologies":null,"evidence":"Java en mi proyecto"},
				 {"kind":"PROJECT","field":null,"slug":null,"name":"Tienda","value":null,"level":null,"description":"Una tienda","technologies":["Java","SQL"],"evidence":"Tienda"}
				]}""";
		String response = JSON.writeValueAsString(java.util.Map.of("choices",
				List.of(java.util.Map.of("message", java.util.Map.of("content", content)))));

		List<RawSuggestion> result = adapter((url, key, body, timeout) -> response).extract("texto", SampleCv.catalog());

		assertThat(result).hasSize(2);
		assertThat(result.get(0).slug()).isEqualTo("java");
		assertThat(result.get(0).level()).isEqualTo(2);
		assertThat(result.get(1).technologies()).containsExactly("Java", "SQL");
	}

	@Test
	@DisplayName("009-29: una falla de red o una respuesta ilegible es un fallo de la plataforma, sin contenido en el mensaje")
	void t009_29_failuresAreCleanErrors() {
		assertThatThrownBy(() -> adapter((url, key, body, timeout) -> {
			throw new IOException("detalle con MARCASECRETA");
		}).extract("MARCASECRETA", SampleCv.catalog())).isInstanceOf(CvExtractionException.class)
				.hasMessageNotContaining("MARCASECRETA");
		assertThatThrownBy(() -> adapter((url, key, body, timeout) -> "no es json MARCASECRETA")
				.extract("texto", SampleCv.catalog())).isInstanceOf(CvExtractionException.class)
						.hasMessageNotContaining("MARCASECRETA");
		assertThatThrownBy(() -> adapter((url, key, body, timeout) -> "{\"choices\":[]}").extract("texto",
				SampleCv.catalog())).isInstanceOf(CvExtractionException.class);
	}

	@Test
	@DisplayName("009-11: la información del adaptador incluye el modelo y la versión de las instrucciones")
	void t009_11_info() {
		var info = adapter((url, key, body, timeout) -> "").info();

		assertThat(info.name()).isEqualTo("openai");
		assertThat(info.model()).isEqualTo("modelo-de-prueba");
		assertThat(info.ruleSetVersion()).isEqualTo(OpenAiCvExtractionAdapter.INSTRUCTIONS_VERSION);
	}

}
