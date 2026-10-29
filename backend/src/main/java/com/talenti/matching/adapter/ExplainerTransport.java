package com.talenti.matching.adapter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Transporte HTTP hacia OpenAI de la explicación, separado para probar el adaptador sin red (015-16: la suite de
 * contrato corre con un transporte simulado). Propio de matching (no usa el de practice, T5). La implementación real
 * usa el cliente HTTP del JDK. Nunca registra el cuerpo ni la clave; los errores llevan solo el estado HTTP.
 */
@FunctionalInterface
public interface ExplainerTransport {

	/** POST JSON a {@code path} (por ejemplo {@code /chat/completions}); devuelve el cuerpo de la respuesta. */
	String postJson(String path, String body, Duration timeout) throws IOException;

	/** Transporte real. Falla al crearse si no hay clave (el arranque lo dice). */
	static ExplainerTransport http(String baseUrl, String apiKey) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("La explicación con IA está encendida (talenti.ai.explanation.enabled=true) "
					+ "pero falta la variable OPENAI_API_KEY. Ponla en tu .env o apágala con TALENTI_AI_EXPLANATION_ENABLED=false.");
		}
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NEVER).build();
		String base = baseUrl.replaceAll("/+$", "");
		return (path, body, timeout) -> {
			HttpRequest request = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
					.header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(body)).build();
			try {
				HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
				if (response.statusCode() / 100 != 2) {
					throw new IOException("El servicio de IA respondió con el estado " + response.statusCode());
				}
				return response.body();
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Llamada a la IA interrumpida");
			}
		};
	}

}
