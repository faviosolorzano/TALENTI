package com.talenti.support;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente HTTP de pruebas que se comporta como el frontend: guarda las cookies que recibe, lee la cookie
 * {@code XSRF-TOKEN} y la devuelve en {@code X-XSRF-TOKEN}. Las cookies se manejan a mano (y no con
 * {@code CookieManager}) para poder probar también el perfil prod, cuyas cookies son {@code Secure} aunque la
 * prueba hable HTTP. Cada cliente se presenta con un origen propio ({@code X-Real-IP}) para no compartir el
 * límite de solicitudes de ingreso con otras pruebas.
 */
public final class ApiClient {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final AtomicInteger ORIGINS = new AtomicInteger(1);

	/** Respuesta con utilidades para las comprobaciones. */
	public record Response(int status, HttpHeaders headers, String body) {

		public JsonNode json() {
			return JSON.readTree(body);
		}

		public String header(String name) {
			return headers.firstValue(name).orElse(null);
		}

		/** Todos los Set-Cookie de la respuesta, tal como llegaron. */
		public List<String> setCookies() {
			return headers.allValues("Set-Cookie");
		}

		/** El Set-Cookie completo de la cookie {@code name}, si vino. */
		public Optional<String> setCookie(String name) {
			return setCookies().stream().filter(value -> value.startsWith(name + "=")).findFirst();
		}

	}

	private final String base;

	private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	private final Map<String, String> cookies = new LinkedHashMap<>();

	private String origin;

	public ApiClient(int port) {
		this.base = "http://localhost:" + port;
		int n = ORIGINS.getAndIncrement();
		this.origin = "10." + ((n >> 16) & 0xff) + "." + ((n >> 8) & 0xff) + "." + (n & 0xff);
	}

	public ApiClient origin(String origin) {
		this.origin = origin;
		return this;
	}

	public String origin() {
		return origin;
	}

	public String cookie(String name) {
		return cookies.get(name);
	}

	public void setCookie(String name, String value) {
		cookies.put(name, value);
	}

	public void forgetCookie(String name) {
		cookies.remove(name);
	}

	public Map<String, String> cookies() {
		return Map.copyOf(cookies);
	}

	public Response get(String path) {
		return send("GET", path, null, false);
	}

	/** POST con el token CSRF que el frontend leería de la cookie (pide el token primero si aún no lo tiene). */
	public Response post(String path, Object body) {
		if (cookies.get("XSRF-TOKEN") == null) {
			get("/api/v1/auth/csrf");
		}
		return send("POST", path, body, true);
	}

	/** GET con cabeceras propias, por ejemplo {@code If-None-Match}. */
	public Response get(String path, Map<String, String> headers) {
		return send("GET", path, null, null, headers);
	}

	/** PUT con el token CSRF que el frontend leería de la cookie (lo pide primero si aún no lo tiene). */
	public Response put(String path, Object body) {
		return withCsrf("PUT", path, body);
	}

	/** DELETE con el token CSRF que el frontend leería de la cookie (lo pide primero si aún no lo tiene). */
	public Response delete(String path) {
		return withCsrf("DELETE", path, null);
	}

	private Response withCsrf(String method, String path, Object body) {
		if (cookies.get("XSRF-TOKEN") == null) {
			get("/api/v1/auth/csrf");
		}
		return send(method, path, body, cookies.get("XSRF-TOKEN"), Map.of());
	}

	/**
	 * Escritura con el método dado y el token CSRF indicado tal cual (nulo = sin cabecera). Para probar que una ruta
	 * rechaza una escritura sin token o con uno falso, sea PUT, POST o DELETE.
	 */
	public Response writeWithToken(String method, String path, Object body, String csrfToken) {
		return send(method, path, body, csrfToken, Map.of());
	}

	/**
	 * Escritura con el cuerpo enviado en trozos ({@code Transfer-Encoding: chunked}, sin {@code Content-Length}),
	 * con el token CSRF de la sesión. Para probar topes de tamaño que no pueden fiarse de la cabecera.
	 */
	public Response writeChunked(String method, String path, byte[] body) {
		return writeChunked(method, path, body, "application/json");
	}

	public Response writeChunked(String method, String path, byte[] body, String contentType) {
		if (cookies.get("XSRF-TOKEN") == null) {
			get("/api/v1/auth/csrf");
		}
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).header("X-Real-IP", origin)
				.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN")).header("Content-Type", contentType)
				.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
						.collect(Collectors.joining("; ")))
				.method(method, HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(body)));
		try {
			HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			remember(response.headers().allValues("Set-Cookie"));
			return new Response(response.statusCode(), response.headers(), response.body());
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	/**
	 * POST multipart/form-data con un archivo en el campo {@code file} y campos de texto, con el token CSRF de la
	 * sesión (lo pide primero si aún no lo tiene). Para el lector de CV.
	 */
	public Response postMultipart(String path, String fileName, String contentType, byte[] content,
			Map<String, String> fields) {
		return postMultipart(path, fileName, contentType, content, fields, true);
	}

	public Response postMultipart(String path, String fileName, String contentType, byte[] content,
			Map<String, String> fields, boolean withCsrf) {
		if (withCsrf && cookies.get("XSRF-TOKEN") == null) {
			get("/api/v1/auth/csrf");
		}
		String boundary = "----talenti" + java.util.UUID.randomUUID().toString().replace("-", "");
		java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
		try {
			for (Map.Entry<String, String> field : fields.entrySet()) {
				body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
						+ "\"\r\n\r\n" + field.getValue() + "\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
			if (content != null) {
				body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName
						+ "\"\r\nContent-Type: " + contentType + "\r\n\r\n")
						.getBytes(java.nio.charset.StandardCharsets.UTF_8));
				body.write(content);
				body.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
			}
			body.write(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).header("X-Real-IP", origin)
				.header("Content-Type", "multipart/form-data; boundary=" + boundary)
				.POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
		if (!cookies.isEmpty()) {
			request.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
					.collect(Collectors.joining("; ")));
		}
		if (withCsrf) {
			request.header("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
		}
		try {
			HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			remember(response.headers().allValues("Set-Cookie"));
			return new Response(response.statusCode(), response.headers(), response.body());
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	public Response postWithoutCsrf(String path, Object body) {
		return send("POST", path, body, false);
	}

	/** POST con un token CSRF falso. */
	public Response postWithCsrf(String path, Object body, String token) {
		return send("POST", path, body, token);
	}

	private Response send(String method, String path, Object body, boolean withCsrf) {
		return send(method, path, body, withCsrf ? cookies.get("XSRF-TOKEN") : null, Map.of());
	}

	private Response send(String method, String path, Object body, String csrfToken) {
		return send(method, path, body, csrfToken, Map.of());
	}

	private Response send(String method, String path, Object body, String csrfToken, Map<String, String> extra) {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).header("X-Real-IP", origin);
		extra.forEach(request::header);
		if (!cookies.isEmpty()) {
			request.header("Cookie", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
					.collect(Collectors.joining("; ")));
		}
		if (csrfToken != null) {
			request.header("X-XSRF-TOKEN", csrfToken);
		}
		if (body != null) {
			request.header("Content-Type", "application/json");
			request.method(method, HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
		}
		else if ("POST".equals(method)) {
			request.POST(HttpRequest.BodyPublishers.noBody());
		}
		else if ("GET".equals(method)) {
			request.GET();
		}
		else {
			request.method(method, HttpRequest.BodyPublishers.noBody());
		}
		try {
			HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
			remember(response.headers().allValues("Set-Cookie"));
			return new Response(response.statusCode(), response.headers(), response.body());
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	private void remember(List<String> setCookieHeaders) {
		for (String header : new ArrayList<>(setCookieHeaders)) {
			String[] parts = header.split(";");
			int eq = parts[0].indexOf('=');
			String name = parts[0].substring(0, eq).trim();
			String value = parts[0].substring(eq + 1).trim();
			boolean expired = false;
			for (int i = 1; i < parts.length; i++) {
				if (parts[i].trim().equalsIgnoreCase("Max-Age=0")) {
					expired = true;
				}
			}
			if (expired || value.isEmpty()) {
				cookies.remove(name);
			}
			else {
				cookies.put(name, value);
			}
		}
	}

}
