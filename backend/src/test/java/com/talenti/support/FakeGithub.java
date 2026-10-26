package com.talenti.support;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * GitHub SIMULADO para las pruebas (spec 013, sección 9): autorización, canje del código y API, en un servidor HTTP del
 * JDK en un puerto local. Ninguna prueba sale a github.com ni a api.github.com. Anota cada petición (método, ruta,
 * cabeceras y cuerpo) para contarlas y revisarlas. Todos los datos son inventados.
 */
public final class FakeGithub {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final AtomicLong IDS = new AtomicLong(910_000_000L);

	private static FakeGithub shared;

	/** Una petición recibida. */
	public record Request(String method, String path, String query, Map<String, String> headers, String body) {

		public String header(String name) {
			return headers.get(name.toLowerCase(java.util.Locale.ROOT));
		}

	}

	/** Una cuenta inventada con sus repositorios y lenguajes. */
	public static final class Account {

		public final long id;

		public volatile String login;

		public volatile String type = "User";

		public final List<Map<String, Object>> repos = new CopyOnWriteArrayList<>();

		/** Bytes por lenguaje, por nombre de repositorio. */
		public final Map<String, Map<String, Long>> languages = new ConcurrentHashMap<>();

		Account(long id, String login) {
			this.id = id;
			this.login = login;
		}

		/** Agrega un repositorio público con lenguajes (pares nombre, bytes) y temas. */
		public Account repo(String name, String description, boolean fork, List<String> topics, Object... languageBytes) {
			Map<String, Object> repo = new LinkedHashMap<>();
			repo.put("id", IDS.incrementAndGet());
			repo.put("owner", Map.of("id", id, "login", login));
			repo.put("name", name);
			repo.put("description", description);
			repo.put("html_url", "https://github.com/" + login + "/" + name);
			repo.put("private", false);
			repo.put("visibility", "public");
			repo.put("fork", fork);
			repo.put("archived", false);
			repo.put("topics", topics);
			repo.put("pushed_at", Instant.now().minusSeconds(repos.size() * 60L).toString());
			repo.put("stargazers_count", 999);
			repo.put("forks_count", 77);
			repo.put("watchers_count", 999);
			Map<String, Long> bytes = new LinkedHashMap<>();
			for (int i = 0; i + 1 < languageBytes.length; i += 2) {
				bytes.put((String) languageBytes[i], ((Number) languageBytes[i + 1]).longValue());
			}
			repo.put("language", bytes.isEmpty() ? null : bytes.keySet().iterator().next());
			repos.add(repo);
			languages.put(name, bytes);
			return this;
		}

		public void removeRepo(String name) {
			repos.removeIf(repo -> name.equals(repo.get("name")));
			languages.remove(name);
		}

	}

	private final HttpServer server;

	private final List<Request> requests = new CopyOnWriteArrayList<>();

	private final Map<String, Account> accountsByLogin = new ConcurrentHashMap<>();

	private final Map<Long, Account> accountsById = new ConcurrentHashMap<>();

	private final Map<String, String> tokenByCode = new ConcurrentHashMap<>();

	private final Map<String, Account> accountByToken = new ConcurrentHashMap<>();

	/** Respuesta forzada para las rutas cuyo camino empieza con una clave (por ejemplo "/users/"). */
	private final Map<String, Function<HttpExchange, Boolean>> overrides = new ConcurrentHashMap<>();

	private volatile Clock clock = Clock.systemUTC();

	private volatile int rateRemaining = 59;

	private volatile long rateResetEpoch = 0;

	private FakeGithub() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(Executors.newFixedThreadPool(8, runnable -> {
			Thread thread = new Thread(runnable, "fake-github");
			thread.setDaemon(true);
			return thread;
		}));
		server.createContext("/", this::handle);
		server.start();
	}

	/** Un único servidor simulado para toda la ejecución (cada contexto de Spring apunta a él). */
	public static synchronized FakeGithub shared() {
		if (shared == null) {
			try {
				shared = new FakeGithub();
			}
			catch (IOException e) {
				throw new IllegalStateException(e);
			}
		}
		return shared;
	}

	public String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** Vuelve al comportamiento normal: sin fallas forzadas y con presupuesto de sobra. */
	public void reset() {
		overrides.clear();
		requests.clear();
		rateRemaining = 59;
		rateResetEpoch = 0;
	}

	public void useClock(Clock clock) {
		this.clock = clock;
	}

	public Account account(String loginPrefix) {
		long id = IDS.incrementAndGet();
		Account account = new Account(id, loginPrefix + "-" + id);
		accountsByLogin.put(account.login.toLowerCase(java.util.Locale.ROOT), account);
		accountsById.put(id, account);
		return account;
	}

	/** Cambia el {@code login} de una cuenta (como cuando la persona la renombra en GitHub). */
	public void rename(Account account, String newLogin) {
		accountsByLogin.remove(account.login.toLowerCase(java.util.Locale.ROOT));
		account.login = newLogin;
		accountsByLogin.put(newLogin.toLowerCase(java.util.Locale.ROOT), account);
		for (Map<String, Object> repo : account.repos) {
			repo.put("html_url", "https://github.com/" + newLogin + "/" + repo.get("name"));
		}
	}

	/**
	 * La persona inició sesión en GitHub y autorizó: devuelve el {@code code} que GitHub pondría en la vuelta. El
	 * token que se entregará al canjearlo es una marca única, para buscarla después en la base y los registros.
	 */
	public String authorize(Account account) {
		String code = "code" + UUID.randomUUID().toString().replace("-", "");
		String token = tokenFor(code);
		accountByToken.put(token, account);
		tokenByCode.put(code, token);
		return code;
	}

	/** El token simulado que corresponde a un {@code code}. */
	public String tokenFor(String code) {
		return "gho_marcaToken" + code.substring(4, 24);
	}

	public void setRate(int remaining, Instant reset) {
		this.rateRemaining = remaining;
		this.rateResetEpoch = reset.getEpochSecond();
	}

	/** Respuesta forzada para las rutas que empiezan con {@code pathPrefix}: estado y cuerpo, con cabeceras extra. */
	public void fail(String pathPrefix, int status, String body, Map<String, String> headers) {
		overrides.put(pathPrefix, exchange -> {
			headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
			send(exchange, status, body);
			return true;
		});
	}

	/** Responde, pero después de {@code millis} (para probar el tiempo de espera). */
	public void slow(String pathPrefix, long millis) {
		overrides.put(pathPrefix, exchange -> {
			try {
				Thread.sleep(millis);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			send(exchange, 200, "[]");
			return true;
		});
	}

	public void clearFailure(String pathPrefix) {
		overrides.remove(pathPrefix);
	}

	public List<Request> requests() {
		return Collections.unmodifiableList(new ArrayList<>(requests));
	}

	/** Peticiones a la API (sin contar el canje del código). */
	public List<Request> apiRequests() {
		return requests().stream().filter(r -> !r.path().startsWith("/login/")).toList();
	}

	// ------------------------------------------------------------------------------------------ servidor

	private void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			String path = exchange.getRequestURI().getRawPath();
			String query = exchange.getRequestURI().getRawQuery();
			Map<String, String> headers = new LinkedHashMap<>();
			exchange.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
			String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
			requests.add(new Request(exchange.getRequestMethod(), path, query, headers, body));
			for (Map.Entry<String, Function<HttpExchange, Boolean>> override : overrides.entrySet()) {
				if (path.startsWith(override.getKey())) {
					override.getValue().apply(exchange);
					return;
				}
			}
			route(exchange, path, headers, body);
		}
		catch (RuntimeException e) {
			send(exchange, 500, "{}");
		}
	}

	private void route(HttpExchange exchange, String path, Map<String, String> headers, String body) throws IOException {
		if (path.equals("/login/oauth/access_token") && "POST".equals(exchange.getRequestMethod())) {
			Map<String, String> form = form(body);
			String token = tokenByCode.remove(form.getOrDefault("code", ""));
			ObjectNode node = JSON.createObjectNode();
			if (token == null) {
				node.put("error", "bad_verification_code");
				node.put("error_description", "The code passed is incorrect or expired.");
			}
			else {
				node.put("access_token", token);
				node.put("token_type", "bearer");
				node.put("scope", "");
			}
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			send(exchange, 200, JSON.writeValueAsString(node));
			return;
		}
		rateHeaders(exchange);
		if (path.equals("/user")) {
			String auth = headers.getOrDefault("authorization", "");
			Account account = accountByToken.get(auth.replaceFirst("^Bearer ", ""));
			if (account == null) {
				send(exchange, 401, "{\"message\":\"Bad credentials\"}");
				return;
			}
			send(exchange, 200, user(account));
			return;
		}
		if (path.startsWith("/user/")) {
			Account account = accountsById.get(Long.parseLong(path.substring("/user/".length())));
			send(exchange, account == null ? 404 : 200, account == null ? "{}" : user(account));
			return;
		}
		if (path.startsWith("/users/") && path.endsWith("/repos")) {
			String login = URLDecoder.decode(path.substring("/users/".length(), path.length() - "/repos".length()),
					StandardCharsets.UTF_8);
			Account account = accountsByLogin.get(login.toLowerCase(java.util.Locale.ROOT));
			if (account == null) {
				send(exchange, 404, "{}");
				return;
			}
			ArrayNode array = JSON.createArrayNode();
			for (Map<String, Object> repo : account.repos) {
				array.add(JSON.valueToTree(repo));
			}
			etagged(exchange, headers, JSON.writeValueAsString(array));
			return;
		}
		if (path.startsWith("/repos/") && path.endsWith("/languages")) {
			String[] parts = path.split("/");
			String owner = URLDecoder.decode(parts[2], StandardCharsets.UTF_8);
			String repo = URLDecoder.decode(parts[3], StandardCharsets.UTF_8);
			Account account = accountsByLogin.get(owner.toLowerCase(java.util.Locale.ROOT));
			Map<String, Long> bytes = account == null ? null : account.languages.get(repo);
			if (bytes == null) {
				send(exchange, 404, "{}");
				return;
			}
			etagged(exchange, headers, JSON.writeValueAsString(bytes));
			return;
		}
		send(exchange, 404, "{}");
	}

	private void rateHeaders(HttpExchange exchange) {
		long reset = rateResetEpoch == 0 ? clock.instant().plusSeconds(3600).getEpochSecond() : rateResetEpoch;
		exchange.getResponseHeaders().add("x-ratelimit-remaining", Integer.toString(rateRemaining));
		exchange.getResponseHeaders().add("x-ratelimit-reset", Long.toString(reset));
	}

	private static void etagged(HttpExchange exchange, Map<String, String> headers, String body) throws IOException {
		String etag = "W/\"" + Integer.toHexString(body.hashCode()) + "\"";
		exchange.getResponseHeaders().add("ETag", etag);
		if (etag.equals(headers.get("if-none-match"))) {
			exchange.sendResponseHeaders(304, -1);
			return;
		}
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		send(exchange, 200, body);
	}

	private static String user(Account account) {
		ObjectNode node = JSON.createObjectNode();
		node.put("id", account.id);
		node.put("login", account.login);
		node.put("type", account.type);
		node.put("followers", 1234);
		node.put("bio", "bio inventada");
		return JSON.writeValueAsString(node);
	}

	private static Map<String, String> form(String body) {
		Map<String, String> result = new LinkedHashMap<>();
		for (String pair : body.split("&")) {
			int eq = pair.indexOf('=');
			if (eq > 0) {
				result.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
						URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
			}
		}
		return result;
	}

	private static void send(HttpExchange exchange, int status, String body) {
		try {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
			if (bytes.length > 0) {
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(bytes);
				}
			}
		}
		catch (IOException e) {
			// El cliente cerró la conexión (por ejemplo, por su tiempo de espera).
		}
	}

}
