package com.talenti.support;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente de pruebas para los flujos SSE de DORA, como lo hará el frontend con {@code fetch}: POST con la cookie de
 * sesión y el token CSRF de un {@link ApiClient}, y lectura del flujo evento por evento, anotando CUÁNDO llegó cada uno
 * ({@link System#nanoTime()}) para medir la latencia desde el cliente.
 */
public final class SseClient {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	/** Un evento recibido. */
	public record Event(String name, JsonNode data, long atNanos) {
	}

	/**
	 * @param sentNanos cuándo terminó de enviarse el pedido (aproximado: justo antes de enviar)
	 * @param body      el cuerpo si no fue un flujo (por ejemplo un Problem Details)
	 */
	public record Result(int status, String contentType, String cacheControl, List<Event> events, String body,
			long sentNanos) {

		public List<String> names() {
			return events.stream().map(Event::name).toList();
		}

		public List<Event> all(String name) {
			return events.stream().filter(e -> e.name().equals(name)).toList();
		}

		public Event first(String name) {
			return events.stream().filter(e -> e.name().equals(name)).findFirst()
					.orElseThrow(() -> new AssertionError("Sin evento " + name + " en " + names() + " " + body));
		}

		public boolean has(String name) {
			return events.stream().anyMatch(e -> e.name().equals(name));
		}

		public JsonNode problem() {
			return JSON.readTree(body);
		}

		/** Milisegundos desde que se envió el pedido hasta el evento. */
		public long millisTo(Event event) {
			return (event.atNanos() - sentNanos) / 1_000_000;
		}

	}

	private final ApiClient api;

	private final String base;

	public SseClient(ApiClient api, int port) {
		this.api = api;
		this.base = "http://localhost:" + port;
	}

	public Result postJson(String path, Object body) {
		byte[] bytes = body == null ? new byte[0] : JSON.writeValueAsBytes(body);
		return send(path, "application/json", bytes, true);
	}

	/** Turno por audio: multipart con el campo {@code audio} y los campos de texto. */
	public Result postAudio(String path, byte[] audio, String declaredType, Map<String, String> fields) {
		String boundary = "----dora" + UUID.randomUUID().toString().replace("-", "");
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (Map.Entry<String, String> field : fields.entrySet()) {
			out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
					+ field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
		}
		if (audio != null) {
			out.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"turno.bin\""
					+ "\r\nContent-Type: " + declaredType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			out.writeBytes(audio);
			out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
		}
		out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
		return send(path, "multipart/form-data; boundary=" + boundary, out.toByteArray(), true);
	}

	/** POST sin token CSRF (para comprobar el 403). */
	public Result postJsonWithoutCsrf(String path, Object body) {
		return send(path, "application/json", JSON.writeValueAsBytes(body), false);
	}

	private Result send(String path, String contentType, byte[] body, boolean withCsrf) {
		if (withCsrf && api.cookie("XSRF-TOKEN") == null) {
			api.get("/api/v1/auth/csrf");
		}
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60))
				.header("X-Real-IP", api.origin()).header("Content-Type", contentType)
				.header("Accept", "text/event-stream, application/problem+json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(body));
		if (!api.cookies().isEmpty()) {
			request.header("Cookie", api.cookies().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
					.collect(Collectors.joining("; ")));
		}
		if (withCsrf) {
			request.header("X-XSRF-TOKEN", api.cookie("XSRF-TOKEN"));
		}
		long sent = System.nanoTime();
		try {
			HttpResponse<InputStream> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
			String type = response.headers().firstValue("Content-Type").orElse("");
			String cache = response.headers().firstValue("Cache-Control").orElse(null);
			if (!type.startsWith("text/event-stream")) {
				try (InputStream input = response.body()) {
					return new Result(response.statusCode(), type, cache, List.of(),
							new String(input.readAllBytes(), StandardCharsets.UTF_8), sent);
				}
			}
			List<Event> events = new ArrayList<>();
			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				String name = null;
				StringBuilder data = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					if (line.isEmpty()) {
						if (name != null || data.length() > 0) {
							events.add(new Event(name == null ? "message" : name,
									data.length() == 0 ? null : JSON.readTree(data.toString()), System.nanoTime()));
						}
						name = null;
						data.setLength(0);
					}
					else if (line.startsWith("event:")) {
						name = line.substring(6).strip();
					}
					else if (line.startsWith("data:")) {
						data.append(line.substring(5));
					}
				}
			}
			return new Result(response.statusCode(), type, cache, events, null, sent);
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

}
