package com.talenti.github.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.talenti.github.config.GithubProperties;
import com.talenti.github.domain.OAuthState;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente de GitHub con el cliente HTTP del JDK (sin dependencias nuevas). Reglas (013-18 a 013-21, 013-27):
 * <ul>
 * <li>{@code Accept: application/vnd.github+json}, {@code X-GitHub-Api-Version}, {@code User-Agent: Talenti};</li>
 * <li>3 s para conectar y 5 s para leer (configurables), <strong>sin reintentos</strong> ni redirecciones;</li>
 * <li>la API pública usa la clave de servidor solo si existe y respeta el {@link RateBudget}; las llamadas con el
 * token del inicio de sesión no tocan ese presupuesto (el límite es de ese token);</li>
 * <li>los registros solo llevan el estado HTTP y las llamadas restantes: nunca la URL (lleva el {@code login}), el
 * {@code code}, el token ni la clave.</li>
 * </ul>
 */
public class GithubClient {

	private static final Logger log = LoggerFactory.getLogger(GithubClient.class);

	/** Tope del cuerpo que se acepta de GitHub (100 repositorios caben de sobra). */
	static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

	static final int DESCRIPTION_MAX = 350;

	static final int TOPICS_MAX = 20;

	static final int TOPIC_MAX_LENGTH = 50;

	private static final Pattern LOGIN = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})$");

	private final GithubProperties properties;

	private final RateBudget budget;

	private final JsonMapper json;

	private final HttpClient http;

	private final Clock clock;

	public GithubClient(GithubProperties properties, RateBudget budget, JsonMapper json, Clock clock) {
		this.properties = properties;
		this.budget = budget;
		this.json = json;
		this.clock = clock;
		this.http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout())
				.followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
	}

	public static RateBudget budget(GithubProperties properties, Clock clock) {
		return new RateBudget(properties.reserveCalls(), clock);
	}

	// ------------------------------------------------------------------------------------------ OAuth (013-27)

	/**
	 * Canjea el {@code code} por un token. {@link OAuthExchangeFailedException} si GitHub devuelve {@code error};
	 * {@link GithubUnavailableException} si no responde o la respuesta no tiene la forma esperada.
	 */
	public AccessToken exchangeCode(String code) {
		GithubProperties.OAuth oauth = properties.oauth();
		String form = "client_id=" + OAuthState.encode(oauth.clientId()) + "&client_secret="
				+ OAuthState.encode(oauth.clientSecret()) + "&code=" + OAuthState.encode(code) + "&redirect_uri="
				+ OAuthState.encode(oauth.redirectUri());
		HttpRequest request = HttpRequest.newBuilder(URI.create(OAuthState.trimSlash(oauth.baseUrl())
				+ "/login/oauth/access_token")).timeout(properties.readTimeout())
				.header("Accept", "application/json").header("User-Agent", "Talenti")
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(form)).build();
		Response response;
		try {
			response = send(request, false);
		}
		catch (GithubNotFoundException e) {
			throw new GithubUnavailableException("canje: 404");
		}
		if (response.status() != 200) {
			if (response.status() >= 400 && response.status() < 500) {
				throw new OAuthExchangeFailedException();
			}
			throw new GithubUnavailableException("canje: estado inesperado");
		}
		JsonNode body = parse(response.body());
		if (body.hasNonNull("error")) {
			throw new OAuthExchangeFailedException();
		}
		JsonNode token = body.get("access_token");
		if (token == null || !token.isString() || token.asString().isBlank()) {
			throw new GithubUnavailableException("canje: respuesta sin token");
		}
		return new AccessToken(token.asString());
	}

	/** {@code GET /user} con el token del canje: id numérico, {@code login} y tipo. */
	public GithubUser user(AccessToken token) {
		Response response = send(api("/user", token, null), false);
		requireOk(response);
		return parseUser(parse(response.body()));
	}

	/** {@code GET /user/{id}} por la API pública: el {@code login} vigente de esa cuenta (013-05). */
	public GithubUser userById(long githubUserId) {
		Response response = send(api("/user/" + githubUserId, null, null), true);
		requireOk(response);
		return parseUser(parse(response.body()));
	}

	// ------------------------------------------------------------------------------------------ API (013-06, 013-08)

	/**
	 * {@code GET /users/{login}/repos?type=owner&sort=pushed&per_page=100}, hasta {@code max-pages} páginas. Con
	 * {@code token} (durante la vinculación) o por la API pública. El {@code ETag} se usa en la primera página: un 304
	 * conserva lo guardado.
	 */
	public Fetched<List<RepoData>> repositories(String login, AccessToken token, String etag) {
		List<RepoData> result = new ArrayList<>();
		String firstEtag = null;
		for (int page = 1; page <= Math.max(1, properties.maxPages()); page++) {
			String path = "/users/" + OAuthState.encode(login) + "/repos?type=owner&sort=pushed&per_page=100&page="
					+ page;
			Response response = send(api(path, token, page == 1 ? etag : null), token == null);
			if (page == 1 && response.status() == 304) {
				return Fetched.unchanged(etag);
			}
			requireOk(response);
			if (page == 1) {
				firstEtag = response.etag();
			}
			JsonNode body = parse(response.body());
			if (!body.isArray()) {
				throw new GithubUnavailableException("repositorios: forma inesperada");
			}
			for (JsonNode node : body) {
				RepoData repo = parseRepo(node);
				if (repo != null) {
					result.add(repo);
				}
			}
			if (body.size() < 100) {
				break;
			}
		}
		return new Fetched<>(List.copyOf(result), firstEtag, false);
	}

	/** {@code GET /repos/{owner}/{repo}/languages}: bytes por lenguaje. Una llamada por repositorio (013-08). */
	public Fetched<Map<String, Long>> languages(String owner, String repo, String etag) {
		String path = "/repos/" + OAuthState.encode(owner) + "/" + OAuthState.encode(repo) + "/languages";
		Response response = send(api(path, null, etag), true);
		if (response.status() == 304) {
			return Fetched.unchanged(etag);
		}
		requireOk(response);
		JsonNode body = parse(response.body());
		if (!body.isObject()) {
			throw new GithubUnavailableException("lenguajes: forma inesperada");
		}
		Map<String, Long> bytes = new LinkedHashMap<>();
		for (Map.Entry<String, JsonNode> entry : body.properties()) {
			if (!entry.getValue().isIntegralNumber() || !entry.getValue().canConvertToLong()) {
				throw new GithubUnavailableException("lenguajes: forma inesperada");
			}
			bytes.put(entry.getKey(), entry.getValue().longValue());
		}
		return new Fetched<>(bytes, response.etag(), false);
	}

	// ------------------------------------------------------------------------------------------ HTTP

	private record Response(int status, String body, String etag) {

		@Override
		public String toString() {
			return "Response[status=" + status + "]";
		}

	}

	private HttpRequest api(String path, AccessToken token, String etag) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(OAuthState.trimSlash(properties.baseUrl()) + path))
				.timeout(properties.readTimeout()).header("Accept", "application/vnd.github+json")
				.header("X-GitHub-Api-Version", properties.apiVersion()).header("User-Agent", "Talenti").GET();
		if (token != null) {
			builder.header("Authorization", "Bearer " + token.value());
		}
		else if (properties.hasToken()) {
			builder.header("Authorization", "Bearer " + properties.token());
		}
		if (etag != null && !etag.isBlank()) {
			builder.header("If-None-Match", etag);
		}
		return builder.build();
	}

	/**
	 * Envía sin reintentos. {@code publicBudget}: la llamada cuenta para el presupuesto de la API pública (se revisa
	 * antes y se anota después). 403/429 de límite → {@link GithubRateLimitedException}; 404 →
	 * {@link GithubNotFoundException}; 5xx, red o tiempo de espera → {@link GithubUnavailableException}.
	 */
	private Response send(HttpRequest request, boolean publicBudget) {
		if (publicBudget) {
			budget.check();
		}
		HttpResponse<InputStream> response;
		String body;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
			try (InputStream input = response.body()) {
				byte[] bytes = input.readNBytes(MAX_BODY_BYTES + 1);
				if (bytes.length > MAX_BODY_BYTES) {
					throw new GithubUnavailableException("respuesta demasiado grande");
				}
				body = new String(bytes, StandardCharsets.UTF_8);
			}
		}
		catch (IOException e) {
			// Tiempo de espera, red o conexión rechazada: solo la clase, nunca la URL.
			log.info("GitHub no respondió ({})", e.getClass().getSimpleName());
			throw new GithubUnavailableException("sin respuesta");
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new GithubUnavailableException("interrumpido");
		}
		int status = response.statusCode();
		String remaining = response.headers().firstValue("x-ratelimit-remaining").orElse(null);
		String reset = response.headers().firstValue("x-ratelimit-reset").orElse(null);
		if (publicBudget) {
			budget.record(remaining, reset);
		}
		log.info("GitHub respondió {} (llamadas restantes: {})", status, remaining == null ? "?" : remaining);
		if (status == 429 || (status == 403 && ("0".equals(remaining == null ? null : remaining.strip())
				|| response.headers().firstValue("retry-after").isPresent()))) {
			Instant until = retryAt(response.headers().firstValue("retry-after").orElse(null), reset);
			if (publicBudget) {
				budget.block(until);
			}
			throw new GithubRateLimitedException(until);
		}
		if (status == 404) {
			throw new GithubNotFoundException();
		}
		if (status >= 500 || status == 403 || status == 401 || (status >= 300 && status < 400 && status != 304)) {
			throw new GithubUnavailableException("estado " + status);
		}
		return new Response(status, body, response.headers().firstValue("etag").orElse(null));
	}

	private static void requireOk(Response response) {
		if (response.status() != 200) {
			throw new GithubUnavailableException("estado " + response.status());
		}
	}

	private Instant retryAt(String retryAfter, String reset) {
		Integer seconds = RateBudget.parseInt(retryAfter);
		if (seconds != null && seconds >= 0) {
			return clock.instant().plusSeconds(seconds);
		}
		Instant at = RateBudget.parseEpoch(reset);
		return at != null ? at : clock.instant().plus(Duration.ofMinutes(1));
	}

	private JsonNode parse(String body) {
		try {
			JsonNode node = json.readTree(body);
			if (node == null || node.isMissingNode()) {
				throw new GithubUnavailableException("respuesta vacía");
			}
			return node;
		}
		catch (GithubUnavailableException e) {
			throw e;
		}
		catch (RuntimeException e) {
			throw new GithubUnavailableException("respuesta que no es JSON");
		}
	}

	private static GithubUser parseUser(JsonNode node) {
		JsonNode id = node.get("id");
		JsonNode login = node.get("login");
		JsonNode type = node.get("type");
		if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || id.longValue() <= 0 || login == null
				|| !login.isString() || !LOGIN.matcher(login.asString()).matches()) {
			throw new GithubUnavailableException("usuario: forma inesperada");
		}
		return new GithubUser(id.longValue(), login.asString(), type != null && type.isString() ? type.asString() : "");
	}

	/** Un repositorio válido y público, o nulo si le falta algo o no cumple los límites (no se guarda). */
	private static RepoData parseRepo(JsonNode node) {
		if (!node.isObject()) {
			throw new GithubUnavailableException("repositorio: forma inesperada");
		}
		JsonNode id = node.get("id");
		String name = text(node, "name");
		String url = text(node, "html_url");
		if (id == null || !id.isIntegralNumber() || !id.canConvertToLong() || name == null || name.isBlank()
				|| name.length() > 100 || url == null || url.length() > 300 || !url.startsWith("https://github.com/")) {
			return null;
		}
		JsonNode privateNode = node.get("private");
		String visibility = text(node, "visibility");
		if ((privateNode != null && privateNode.isBoolean() && privateNode.booleanValue()) || (visibility != null && !"public".equals(visibility))) {
			return null;
		}
		String description = text(node, "description");
		if (description != null) {
			description = description.strip();
			description = description.isEmpty() ? null
					: description.substring(0, Math.min(description.length(), DESCRIPTION_MAX));
		}
		String language = text(node, "language");
		if (language != null && (language.isBlank() || language.length() > 100)) {
			language = null;
		}
		List<String> topics = new ArrayList<>();
		JsonNode topicsNode = node.get("topics");
		if (topicsNode != null && topicsNode.isArray()) {
			for (JsonNode topic : topicsNode) {
				if (topics.size() >= TOPICS_MAX) {
					break;
				}
				if (topic.isString() && !topic.asString().isBlank() && topic.asString().length() <= TOPIC_MAX_LENGTH) {
					topics.add(topic.asString().strip());
				}
			}
		}
		Instant pushedAt = null;
		String pushed = text(node, "pushed_at");
		if (pushed != null) {
			try {
				pushedAt = Instant.parse(pushed);
			}
			catch (DateTimeParseException ignored) {
				pushedAt = null;
			}
		}
		return new RepoData(id.longValue(), name, description, url, language, topics, bool(node, "fork"),
				bool(node, "archived"), pushedAt);
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && value.isString() ? value.asString() : null;
	}

	private static boolean bool(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value != null && value.isBoolean() && value.booleanValue();
	}

}
