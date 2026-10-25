package com.talenti.practice.adapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Transporte HTTP hacia OpenAI, separado para probar los adaptadores sin red (012-31: la suite de contrato corre con un
 * transporte simulado). La implementación real usa el cliente HTTP del JDK (sin dependencias nuevas). Ningún método
 * registra el cuerpo ni la clave; los errores llevan solo el estado HTTP.
 */
public interface OpenAiTransport {

	/** POST JSON y cuerpo de la respuesta como texto. */
	String postJson(String path, String body, Duration timeout) throws IOException;

	/** POST JSON con respuesta en streaming (Server-Sent Events): entrega cada línea en cuanto llega. */
	void postJsonStream(String path, String body, Duration timeout, Consumer<String> onLine) throws IOException;

	/** POST JSON y cuerpo de la respuesta en bytes (audio). */
	byte[] postJsonForBytes(String path, String body, Duration timeout) throws IOException;

	/** POST multipart con campos de texto y un archivo (el audio, solo en memoria). */
	String postMultipart(String path, Map<String, String> fields, String fileField, String fileName, String mime,
			byte[] file, Duration timeout) throws IOException;

	/** Transporte real con {@link HttpClient}. */
	static OpenAiTransport http(String baseUrl, String apiKey) {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		return new OpenAiTransport() {

			private HttpRequest.Builder request(String path, Duration timeout) {
				return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
						.header("Authorization", "Bearer " + apiKey);
			}

			private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
					throws IOException {
				try {
					HttpResponse<T> response = client.send(request, handler);
					if (response.statusCode() / 100 != 2) {
						if (response.body() instanceof InputStream stream) {
							stream.close();
						}
						if (response.body() instanceof Stream<?> lines) {
							lines.close();
						}
						throw new IOException("El servicio de IA respondió con el estado " + response.statusCode());
					}
					return response;
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Llamada a la IA interrumpida");
				}
			}

			@Override
			public String postJson(String path, String body, Duration timeout) throws IOException {
				return send(request(path, timeout).header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
						.body();
			}

			@Override
			public void postJsonStream(String path, String body, Duration timeout, Consumer<String> onLine)
					throws IOException {
				HttpResponse<Stream<String>> response = send(request(path, timeout)
						.header("Content-Type", "application/json").header("Accept", "text/event-stream")
						.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofLines());
				try (Stream<String> lines = response.body()) {
					lines.forEach(line -> {
						if (Thread.currentThread().isInterrupted()) {
							throw new IllegalStateException("Llamada a la IA interrumpida");
						}
						onLine.accept(line);
					});
				}
				catch (IllegalStateException | java.io.UncheckedIOException e) {
					throw new IOException("Se cortó la respuesta de la IA");
				}
			}

			@Override
			public byte[] postJsonForBytes(String path, String body, Duration timeout) throws IOException {
				return send(request(path, timeout).header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
						HttpResponse.BodyHandlers.ofByteArray()).body();
			}

			@Override
			public String postMultipart(String path, Map<String, String> fields, String fileField, String fileName,
					String mime, byte[] file, Duration timeout) throws IOException {
				String boundary = "----talenti" + UUID.randomUUID().toString().replace("-", "");
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				for (Map.Entry<String, String> field : fields.entrySet()) {
					out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
							+ "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
				}
				out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + fileField
						+ "\"; filename=\"" + fileName + "\"\r\nContent-Type: " + mime + "\r\n\r\n")
						.getBytes(StandardCharsets.UTF_8));
				out.writeBytes(file);
				out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
				return send(request(path, timeout).header("Content-Type", "multipart/form-data; boundary=" + boundary)
						.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),
						HttpResponse.BodyHandlers.ofString()).body();
			}

		};
	}

}
