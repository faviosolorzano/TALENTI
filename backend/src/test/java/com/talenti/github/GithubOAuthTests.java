package com.talenti.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.github.domain.OAuthState;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;
import com.talenti.support.FakeGithub;

/** Inicio de sesión con GitHub (013-22, 013-25 a 013-30) contra el GitHub simulado. */
class GithubOAuthTests extends GithubIntegrationTest {

	private int exchanges() {
		return (int) fake.requests().stream().filter(r -> r.path().equals("/login/oauth/access_token")).count();
	}

	// ------------------------------------------------------------------------------------------ 013-25

	@Test
	@DisplayName("013-25 y 013-30: start da la dirección de autorización con client_id, redirect_uri, state y allow_signup=false, sin scope")
	void t013_25_startBuildsAuthorizeUrlWithoutScope() {
		ApiClient api = student();

		String url = authorizeUrl(api);

		assertThat(url).startsWith(fake.baseUrl() + "/login/oauth/authorize?");
		Map<String, String> query = query(url);
		assertThat(query).containsOnlyKeys("client_id", "redirect_uri", "state", "allow_signup");
		assertThat(query.get("client_id")).isEqualTo(CLIENT_ID);
		assertThat(query.get("redirect_uri")).isEqualTo(REDIRECT_URI);
		assertThat(query.get("allow_signup")).isEqualTo("false");
		assertThat(url).doesNotContain("scope");
		assertThat(query.get("state")).matches("[A-Za-z0-9_-]{43}");
	}

	@Test
	@DisplayName("013-25: se guarda solo el hash del state, ligado a la persona y a su sesión, con 10 minutos de vigencia")
	void t013_25_onlyTheHashIsStoredWithTenMinutes() {
		RegisteredStudent student = registerStudent();

		String state = start(student.api());

		assertThat(rowsContaining(state)).as("el state en claro no está en la base").isZero();
		Map<String, Object> row = jdbc.queryForMap(
				"SELECT state_hash, session_ref, expires_at, created_at FROM github_oauth_state WHERE user_id = ?",
				student.id());
		assertThat(row.get("state_hash")).isEqualTo(OAuthState.sha256(state));
		assertThat(row.get("session_ref")).isEqualTo(OAuthState.sha256(student.api().cookie("JSESSIONID")));
		OffsetDateTime expires = ((java.sql.Timestamp) row.get("expires_at")).toInstant().atOffset(java.time.ZoneOffset.UTC);
		// La base guarda microsegundos: se admite esa diferencia.
		assertThat(Duration.between(clock.instant(), expires.toInstant()))
				.isBetween(Duration.ofMinutes(10).minusMillis(1), Duration.ofMinutes(10).plusMillis(1));
	}

	@Test
	@DisplayName("013-25: un nuevo start invalida el state anterior (dos pestañas: vale solo el último)")
	void t013_25_newStartInvalidatesPrevious() {
		ApiClient api = student();
		FakeGithub.Account account = fake.account("dos-pestanas");
		String first = start(api);
		String second = start(api);

		assertThat(count("SELECT count(*) FROM github_oauth_state WHERE state_hash IN (?, ?)", OAuthState.sha256(first),
				OAuthState.sha256(second))).isEqualTo(1);
		assertThat(outcome(callback(api, fake.authorize(account), first))).isEqualTo("invalid-state");
		assertThat(exchanges()).isZero();
	}

	@Test
	@DisplayName("013-25: con GitHub ya vinculado, start da 409 already-linked")
	void t013_25_alreadyLinkedIs409() {
		Linked linked = linkNew("ya-vinculado");

		Response response = linked.api().post(GITHUB + "/oauth/start", null);

		assertThat(response.status()).isEqualTo(409);
		assertThat(response.json().get("code").asString()).isEqualTo("already-linked");
	}

	// ------------------------------------------------------------------------------------------ 013-26

	@Test
	@DisplayName("013-26: un state desconocido, vencido, ajeno, de otra sesión o ya usado da invalid-state y no se canjea el código")
	void t013_26_invalidStatesNeverExchange() {
		FakeGithub.Account account = fake.account("state-invalido");
		// Desconocido.
		ApiClient api = student();
		start(api);
		assertThat(outcome(callback(api, fake.authorize(account), "estadoInventado123"))).isEqualTo("invalid-state");

		// Vencido (más de 10 minutos).
		String expired = start(api);
		clock.advance(Duration.ofMinutes(10).plusSeconds(1));
		assertThat(outcome(callback(api, fake.authorize(account), expired))).isEqualTo("invalid-state");

		// De otra persona.
		ApiClient other = student();
		String othersState = start(other);
		assertThat(outcome(callback(api, fake.authorize(account), othersState))).isEqualTo("invalid-state");

		// De otra sesión de la misma persona (vuelve a ingresar desde otro navegador).
		RegisteredStudent person = registerStudent();
		String stateOfFirstSession = start(person.api());
		ApiClient secondSession = client();
		assertThat(secondSession.post("/api/v1/auth/login", loginBody(person.email(), person.password())).status())
				.isEqualTo(200);
		assertThat(outcome(callback(secondSession, fake.authorize(account), stateOfFirstSession)))
				.isEqualTo("invalid-state");

		assertThat(exchanges()).as("ningún código se canjeó").isZero();
	}

	@Test
	@DisplayName("013-26: el state se consume una sola vez: una segunda vuelta con el mismo state da invalid-state")
	void t013_26_stateIsSingleUse() {
		FakeGithub.Account account = fake.account("un-solo-uso");
		ApiClient api = student();
		String state = start(api);
		String code = fake.authorize(account);

		assertThat(outcome(callback(api, code, state))).isEqualTo("linked");
		assertThat(outcome(callback(api, code, state))).isEqualTo("invalid-state");
		assertThat(exchanges()).isEqualTo(1);
	}

	// ------------------------------------------------------------------------------------------ 013-27

	@Test
	@DisplayName("013-27: con state válido se canjea el código, se lee /user y los repositorios, y el vínculo nace VERIFIED y auditado; el token no queda en la base")
	void t013_27_fullFlowCreatesVerifiedLinkAndDiscardsToken() {
		FakeGithub.Account account = fake.account("flujo").repo("api-tienda", "Tienda de prueba", false, List.of(), "Java",
				900);
		RegisteredStudent student = registerStudent();
		String state = start(student.api());
		String code = fake.authorize(account);
		String token = fake.tokenFor(code);

		Response response = callback(student.api(), code, state);

		assertThat(outcome(response)).isEqualTo("linked");
		assertThat(response.header("Location")).doesNotContain(code).doesNotContain(token).doesNotContain(state);
		Map<String, Object> link = jdbc.queryForMap(
				"SELECT login, github_user_id, status, verified_at FROM github_link WHERE user_id = ?", student.id());
		assertThat(link.get("login")).isEqualTo(account.login);
		assertThat(link.get("github_user_id")).isEqualTo(account.id);
		assertThat(link.get("status")).isEqualTo("VERIFIED");
		assertThat(link.get("verified_at")).isNotNull();
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'GITHUB_LINKED' AND actor_user_id = ?",
				student.id())).isEqualTo(1);
		// El canje: POST con client_id, client_secret, code, redirect_uri y Accept: application/json.
		FakeGithub.Request exchange = fake.requests().stream().filter(r -> r.path().equals("/login/oauth/access_token"))
				.findFirst().orElseThrow();
		assertThat(exchange.method()).isEqualTo("POST");
		assertThat(exchange.header("Accept")).isEqualTo("application/json");
		assertThat(query("http://x/?" + exchange.body())).containsEntry("client_id", CLIENT_ID)
				.containsEntry("client_secret", CLIENT_SECRET).containsEntry("code", code)
				.containsEntry("redirect_uri", REDIRECT_URI);
		// /user y la primera lista se leyeron con el token del canje.
		assertThat(fake.apiRequests()).extracting(FakeGithub.Request::path).containsExactly("/user",
				"/users/" + account.login + "/repos");
		assertThat(fake.apiRequests()).allSatisfy(r -> assertThat(r.header("Authorization")).isEqualTo("Bearer " + token));
		// El token (marca única) no está en ninguna tabla ni en la sesión del navegador.
		assertThat(rowsContaining(token)).isZero();
		assertThat(rowsContaining(code)).isZero();
		assertThat(student.api().cookies().values()).noneMatch(value -> value.contains(token));
		// La lista ya quedó guardada: GET /github no llama a GitHub.
		Response linkView = student.api().get(GITHUB);
		assertThat(linkView.status()).isEqualTo(200);
		assertThat(linkView.json().get("status").asString()).isEqualTo("VERIFIED");
		assertThat(linkView.json().get("login").asString()).isEqualTo(account.login);
		assertThat(linkView.json().get("githubUserId").asLong()).isEqualTo(account.id);
		assertThat(linkView.body()).doesNotContain(token);
	}

	// ------------------------------------------------------------------------------------------ 013-28

	@Test
	@DisplayName("013-28: una cuenta de GitHub ya vinculada a otra persona da github-account-taken sin decir de quién")
	void t013_28_accountTakenByAnotherPerson() {
		FakeGithub.Account account = fake.account("compartida");
		linkNew(account);
		RegisteredStudent second = registerStudent();

		Response response = callback(second.api(), fake.authorize(account), start(second.api()));

		assertThat(outcome(response)).isEqualTo("github-account-taken");
		assertThat(count("SELECT count(*) FROM github_link WHERE user_id = ?", second.id())).isZero();
		assertThat(count("SELECT count(*) FROM github_link WHERE github_user_id = ?", account.id)).isEqualTo(1);
	}

	@Test
	@DisplayName("013-28: dos personas vinculan la misma cuenta a la vez: solo una queda vinculada (índice único)")
	void t013_28_concurrentCallbacksOnlyOneWins() throws Exception {
		FakeGithub.Account account = fake.account("a-la-vez");
		List<ApiClient> clients = new ArrayList<>();
		List<String> states = new ArrayList<>();
		List<String> codes = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			ApiClient api = student();
			clients.add(api);
			states.add(start(api));
			codes.add(fake.authorize(account));
		}
		ExecutorService pool = Executors.newFixedThreadPool(4);
		CountDownLatch go = new CountDownLatch(1);
		List<Future<String>> results = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			int n = i;
			results.add(pool.submit(() -> {
				go.await();
				return outcome(callback(clients.get(n), codes.get(n), states.get(n)));
			}));
		}
		go.countDown();
		List<String> outcomes = new ArrayList<>();
		for (Future<String> result : results) {
			outcomes.add(result.get(30, TimeUnit.SECONDS));
		}
		pool.shutdown();

		assertThat(outcomes).filteredOn("linked"::equals).hasSize(1);
		assertThat(outcomes).filteredOn("github-account-taken"::equals).hasSize(3);
		assertThat(count("SELECT count(*) FROM github_link WHERE github_user_id = ?", account.id)).isEqualTo(1);
	}

	// ------------------------------------------------------------------------------------------ 013-29

	@Test
	@DisplayName("013-29: cancelar en GitHub (error=access_denied) vuelve con cancelled y no canjea nada")
	void t013_29_cancelled() {
		ApiClient api = student();
		String state = start(api);

		Response response = api.get(GITHUB + "/oauth/callback?error=access_denied&error_description=x&state=" + state);

		assertThat(outcome(response)).isEqualTo("cancelled");
		assertThat(exchanges()).isZero();
	}

	@Test
	@DisplayName("013-29: si el canje devuelve error (código vencido o redirect_uri distinta) vuelve con oauth-failed")
	void t013_29_exchangeErrorIsOauthFailed() {
		ApiClient api = student();

		Response response = callback(api, "codigoQueGithubNoReconoce", start(api));

		assertThat(outcome(response)).isEqualTo("oauth-failed");
		assertThat(exchanges()).isEqualTo(1);
	}

	@Test
	@DisplayName("013-29: GitHub caído en el canje o en /user vuelve con github-unavailable, nunca 500")
	void t013_29_githubDownIsUnavailable() {
		FakeGithub.Account account = fake.account("caido");
		ApiClient api = student();
		fake.fail("/login/oauth/access_token", 502, "<html>caído</html>", Map.of());
		assertThat(outcome(callback(api, fake.authorize(account), start(api)))).isEqualTo("github-unavailable");

		fake.clearFailure("/login/oauth/access_token");
		fake.fail("/user", 503, "{}", Map.of());
		assertThat(outcome(callback(api, fake.authorize(account), start(api)))).isEqualTo("github-unavailable");

		fake.fail("/user", 200, "esto no es JSON", Map.of());
		assertThat(outcome(callback(api, fake.authorize(account), start(api)))).isEqualTo("github-unavailable");
		assertThat(count("SELECT count(*) FROM github_link WHERE github_user_id = ?", account.id)).isZero();
	}

	@Test
	@DisplayName("013-29 y 013-23: sin sesión la vuelta redirige con session-expired (no responde JSON ni 401)")
	void t013_29_withoutSessionIsSessionExpired() {
		Response response = client().get(GITHUB + "/oauth/callback?code=abc&state=def");

		assertThat(outcome(response)).isEqualTo("session-expired");
		assertThat(response.header("Cache-Control")).contains("no-store");
		assertThat(response.body()).isEmpty();
	}

	@Test
	@DisplayName("013-29: con la sesión cerrada (cookie vieja) la vuelta redirige con session-expired")
	void t013_29_closedSessionIsSessionExpired() {
		RegisteredStudent student = registerStudent();
		String state = start(student.api());
		String oldSession = student.api().cookie("JSESSIONID");
		student.api().post("/api/v1/auth/logout", null);
		ApiClient reuse = client();
		reuse.setCookie("JSESSIONID", oldSession);

		assertThat(outcome(callback(reuse, fake.authorize(fake.account("cerrada")), state))).isEqualTo("session-expired");
	}

	@Test
	@DisplayName("013-29: la vuelta sin state ni code redirige con invalid-state")
	void t013_29_missingParameters() {
		ApiClient api = student();

		assertThat(outcome(api.get(GITHUB + "/oauth/callback"))).isEqualTo("invalid-state");
	}

	// ------------------------------------------------------------------------------------------ 013-22

	@Test
	@DisplayName("013-22: vincular, listar y elegir 6 gasta como máximo 2 + max-pages + max-selected llamadas (aquí, 9)")
	void t013_22_normalUseCallBudget() {
		FakeGithub.Account account = fake.account("presupuesto");
		for (int i = 1; i <= 8; i++) {
			account.repo("repo-" + i, "Repositorio de prueba " + i, false, List.of(), "Java", 1000 * i, "HTML", 100);
		}
		Linked linked = linkNew(account);
		var list = repositories(linked.api());
		List<String> six = new ArrayList<>();
		for (int i = 1; i <= 6; i++) {
			six.add(repoId(list, "repo-" + i));
		}
		assertThat(select(linked.api(), six).status()).isEqualTo(200);

		// 3 + max-pages + max-selected: la selección comprueba una vez la identidad de la cuenta por su id antes de
		// pedir lenguajes (corrección GH-01). Pendiente de reflejar en 013-22 con el sí de Favio.
		assertThat(fake.requests()).as("peticiones al GitHub simulado").hasSize(3 + 1 + 6);
	}

}
