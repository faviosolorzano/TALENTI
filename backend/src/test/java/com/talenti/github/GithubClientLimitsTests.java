package com.talenti.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import com.talenti.support.ApiClient.Response;
import com.talenti.support.FakeGithub;

import tools.jackson.databind.JsonNode;

/**
 * Cliente de GitHub: límite de la API, clave de servidor, tiempo de espera y sin reintentos (013-18 a 013-21). Contexto
 * propio: clave de servidor inventada y tiempo de lectura de 1 s (para no esperar 5 s en la prueba).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"talenti.demo-data.enabled=true", "talenti.github.token=" + GithubClientLimitsTests.SERVER_TOKEN,
		"talenti.github.read-timeout=1s", "talenti.github.reserve-calls=5" })
class GithubClientLimitsTests extends GithubIntegrationTest {

	/** Clave de servidor inventada con marca única: viaja a GitHub, nunca a una respuesta (013-21). */
	static final String SERVER_TOKEN = "ghp_claveServidorMarcaW4k2";

	@BeforeEach
	void clearRateLimitWindow() {
		// El presupuesto vive en memoria del contexto: se deja vencer cualquier bloqueo de una prueba anterior.
		clock.advance(Duration.ofHours(3));
	}

	private Linked linkedWithRepo(String prefix) {
		Linked linked = linkNew(fake.account(prefix).repo("r1", "Uno", false, List.of(), "Java", 10).repo("r2", "Dos",
				false, List.of(), "Go", 10));
		return linked;
	}

	@Test
	@DisplayName("013-21: con clave de servidor, la API pública lleva Authorization: Bearer; el vínculo usa el token del canje; la clave nunca se devuelve")
	void t013_21_serverTokenIsSentButNeverReturned() {
		Linked linked = linkedWithRepo("clave");
		List<FakeGithub.Request> linking = fake.apiRequests();
		fake.reset();

		Response selection = select(linked.api(), List.of(repoId(repositories(linked.api()), "r1")));

		assertThat(linking).allSatisfy(r -> assertThat(r.header("Authorization")).doesNotContain(SERVER_TOKEN));
		assertThat(fake.apiRequests()).hasSize(2).allSatisfy(r ->
				assertThat(r.header("Authorization")).isEqualTo("Bearer " + SERVER_TOKEN));
		assertThat(fake.apiRequests()).filteredOn(r -> r.path().endsWith("/languages")).singleElement()
				.satisfies(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + SERVER_TOKEN));
		assertThat(selection.body()).doesNotContain(SERVER_TOKEN);
		assertThat(rowsContaining(SERVER_TOKEN)).isZero();
	}

	@Test
	@DisplayName("013-19: con 5 llamadas restantes o menos no se llama más: acciones 503 github-rate-limited con retryAt, Retry-After y la hora en español; lecturas, lo guardado con stale")
	void t013_19_reserveStopsCalls() {
		Linked linked = linkedWithRepo("reserva");
		JsonNode list = repositories(linked.api());
		Instant reset = clock.instant().plusSeconds(1800).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		fake.setRate(5, reset);
		// La comprobación del dueño trae "quedan 5": se detiene antes de pedir los lenguajes.
		assertThat(select(linked.api(), List.of(repoId(list, "r1"))).status()).isEqualTo(503);
		fake.reset();

		Response limited = select(linked.api(), List.of(repoId(list, "r2")));

		assertThat(limited.status()).isEqualTo(503);
		assertThat(limited.json().get("code").asString()).isEqualTo("github-rate-limited");
		assertThat(Instant.parse(limited.json().get("retryAt").asString())).isEqualTo(reset);
		assertThat(Long.parseLong(limited.header("Retry-After"))).isBetween(1790L, 1801L);
		String hour = DateTimeFormatter.ofPattern("HH:mm").format(reset.atZone(ZoneId.of("America/Lima")));
		assertThat(limited.json().get("detail").asString()).isEqualTo(
				"GitHub limita las consultas; vuelve a intentarlo a las " + hour + ".");
		assertThat(fake.requests()).as("no se llamó a GitHub").isEmpty();
		// Lo guardado se sigue leyendo sin GitHub.
		assertThat(repositories(linked.api()).get("items")).hasSize(2);
		assertThat(fake.requests()).isEmpty();
	}

	@Test
	@DisplayName("013-19: un 403 de límite (remaining 0) o un 429 bloquea hasta el reset; las lecturas devuelven lo guardado con stale: true")
	void t013_19_githubLimitResponses() {
		Linked linked = linkedWithRepo("limite");
		clock.advance(Duration.ofHours(25));
		Instant reset = clock.instant().plusSeconds(600);
		fake.fail("/users/", 403, "{\"message\":\"API rate limit exceeded\"}",
				Map.of("x-ratelimit-remaining", "0", "x-ratelimit-reset", Long.toString(reset.getEpochSecond())));

		JsonNode stale = repositories(linked.api());
		int calls = fake.requests().size();
		Response refresh = linked.api().post(GITHUB + "/refresh", null);

		assertThat(stale.get("stale").asBoolean()).isTrue();
		assertThat(stale.get("items")).hasSize(2);
		assertThat(refresh.status()).isEqualTo(503);
		assertThat(refresh.json().get("code").asString()).isEqualTo("github-rate-limited");
		assertThat(fake.requests()).as("bloqueado: ninguna llamada más").hasSize(calls);

		clock.advance(Duration.ofMinutes(11));
		fake.fail("/users/", 429, "{}", Map.of("retry-after", "120"));
		JsonNode again = repositories(linked.api());
		assertThat(again.get("stale").asBoolean()).isTrue();
		Response blocked = linked.api().post(GITHUB + "/refresh", null);
		assertThat(blocked.json().get("code").asString()).isEqualTo("github-rate-limited");
		assertThat(Long.parseLong(blocked.header("Retry-After"))).isBetween(100L, 121L);
	}

	@Test
	@DisplayName("013-18 y 013-20: sin reintentos (un 500 es una sola llamada) y un tiempo de espera vencido da lo guardado o 503, nunca 500")
	void t013_18_noRetriesAndTimeout() {
		Linked linked = linkedWithRepo("espera");
		clock.advance(Duration.ofHours(25));
		fake.reset();
		fake.fail("/users/", 500, "{}", Map.of());

		JsonNode down = repositories(linked.api());

		assertThat(down.get("stale").asBoolean()).isTrue();
		assertThat(fake.requests()).hasSize(2); // identidad + lista (una sola llamada a cada ruta)

		fake.reset();
		fake.slow("/repos/", 2500);
		long startedAt = System.nanoTime();
		Response slow = select(linked.api(), List.of(repoId(down, "r1")));
		long millis = (System.nanoTime() - startedAt) / 1_000_000;

		assertThat(slow.status()).isEqualTo(503);
		assertThat(slow.json().get("code").asString()).isEqualTo("github-unavailable");
		assertThat(millis).as("cortó a 1 s, sin esperar los 2,5 s").isLessThan(2400);
		assertThat(fake.requests()).hasSize(2); // identidad + lenguajes, sin repetir la llamada lenta
	}

}
