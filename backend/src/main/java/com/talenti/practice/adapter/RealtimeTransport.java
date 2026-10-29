package com.talenti.practice.adapter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Transporte HTTP de la llamada en tiempo real, separado para probar el adaptador sin red (012-56: la suite de contrato
 * corre con un transporte simulado). La clave viaja SOLO en la cabecera {@code Authorization} de este transporte, del
 * servidor al proveedor; nunca en el cuerpo, en la respuesta al navegador ni en registros. Ningún método registra el
 * cuerpo; los errores llevan solo el estado HTTP.
 */
public interface RealtimeTransport {

	/** Respuesta del proveedor: estado, cuerpo (la respuesta SDP) y la cabecera {@code Location} (id de la llamada). */
	record Response(int status, String body, String location) {

		@Override
		public String toString() {
			return "Response[status=" + status + "]";
		}

	}

	/** POST multipart solo con campos de texto ({@code sdp} y {@code session}). */
	Response postMultipart(String url, Map<String, String> fields, Duration timeout) throws IOException;

	/** POST sin cuerpo (colgar). Devuelve el estado HTTP. */
	int post(String url, Duration timeout) throws IOException;

	/** Transporte real con el cliente HTTP del JDK. */
	static RealtimeTransport http(String apiKey) {
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
		return new RealtimeTransport() {

			@Override
			public Response postMultipart(String url, Map<String, String> fields, Duration timeout) throws IOException {
				String boundary = "----talenti" + UUID.randomUUID().toString().replace("-", "");
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				for (Map.Entry<String, String> field : fields.entrySet()) {
					String contentType = "session".equals(field.getKey()) ? "application/json" : "application/sdp";
					out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
							+ "\"\r\nContent-Type: " + contentType + "\r\n\r\n" + field.getValue() + "\r\n")
							.getBytes(StandardCharsets.UTF_8));
				}
				out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
				HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
						.header("Authorization", "Bearer " + apiKey)
						.header("Content-Type", "multipart/form-data; boundary=" + boundary)
						.POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
				HttpResponse<String> response = send(request, HttpResponse.BodyHandlers.ofString());
				return new Response(response.statusCode(), response.body(),
						response.headers().firstValue("Location").orElse(null));
			}

			@Override
			public int post(String url, Duration timeout) throws IOException {
				HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
						.header("Authorization", "Bearer " + apiKey).POST(HttpRequest.BodyPublishers.noBody()).build();
				return send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
			}

			private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
					throws IOException {
				try {
					return client.send(request, handler);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Llamada en tiempo real interrumpida");
				}
			}

		};
	}

}
