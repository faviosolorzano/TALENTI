package com.talenti.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.talenti.github.config.GithubProperties;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;
import com.talenti.support.FakeGithub;

import tools.jackson.databind.JsonNode;

/** Repositorios: lista, caché, selección, lenguajes, actualizar, renombre, fallas y vitrina (013-05 a 013-09, 013-16, 013-20). */
class GithubRepositoriesTests extends GithubIntegrationTest {

	@Autowired
	private GithubShowcase showcase;

	@Autowired
	private GithubProperties properties;

	private static List<String> names(JsonNode list) {
		List<String> names = new ArrayList<>();
		list.get("items").forEach(item -> names.add(item.get("name").asString()));
		return names;
	}

	private static JsonNode item(JsonNode list, String name) {
		for (JsonNode item : list.get("items")) {
			if (item.get("name").asString().equals(name)) {
				return item;
			}
		}
		throw new AssertionError("No está " + name);
	}

	// ------------------------------------------------------------------------------------------ 013-06

	@Test
	@DisplayName("013-06: se guardan solo los campos permitidos; sin forks; archivados con su marca; descripción hasta 350; enlace de github.com")
	void t013_06_onlyAllowedFieldsAndNoForks() {
		FakeGithub.Account account = fake.account("campos")
				.repo("propio", "x".repeat(400), false, List.of("spring-boot", "api"), "Java", 800, "HTML", 200)
				.repo("copiado", "Un fork", true, List.of(), "Python", 100)
				.repo("viejo", "Archivado", false, List.of(), "PHP", 100);
		account.repos.get(2).put("archived", true);
		account.repo("malo", "Enlace raro", false, List.of(), "Go", 100);
		account.repos.get(3).put("html_url", "https://example.com/otro");

		Linked linked = linkNew(account);
		JsonNode list = repositories(linked.api());

		assertThat(names(list)).containsExactlyInAnyOrder("propio", "viejo");
		JsonNode own = item(list, "propio");
		assertThat(own.get("description").asString()).hasSize(350);
		assertThat(own.get("url").asString()).isEqualTo("https://github.com/" + account.login + "/propio");
		assertThat(own.get("primaryLanguage").asString()).isEqualTo("Java");
		assertThat(own.get("topics")).extracting(JsonNode::asString).containsExactly("spring-boot", "api");
		assertThat(own.get("archived").asBoolean()).isFalse();
		assertThat(own.get("selected").asBoolean()).isFalse();
		assertThat(own.get("pushedAt").isNull()).isFalse();
		assertThat(item(list, "viejo").get("archived").asBoolean()).isTrue();
		// Ni estrellas, ni forks contados, ni seguidores: no se piden ni se devuelven.
		assertThat(list.toString()).doesNotContain("stargazers").doesNotContain("forks_count").doesNotContain("followers")
				.doesNotContain("watchers");
		assertThat(own.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("id", "name",
				"description", "url", "primaryLanguage", "languages", "topics", "archived", "pushedAt", "selected");
		assertThat(fake.apiRequests()).filteredOn(r -> r.path().endsWith("/repos")).singleElement()
				.satisfies(r -> assertThat(r.query()).contains("type=owner").contains("sort=pushed")
						.contains("per_page=100"));
	}

	// ------------------------------------------------------------------------------------------ 013-07

	@Test
	@DisplayName("013-07: la lista guardada se devuelve sin llamar a GitHub mientras tenga menos de 24 h; vacía es válida")
	void t013_07_cachedListAndEmptyIsValid() {
		Linked linked = linkNew("sin-repos");
		fake.reset();

		JsonNode list = repositories(linked.api());

		assertThat(list.get("items")).isEmpty();
		assertThat(list.get("stale").asBoolean()).isFalse();
		assertThat(list.get("fetchedAt").isNull()).isFalse();
		assertThat(fake.requests()).isEmpty();
	}

	@Test
	@DisplayName("013-07 y 013-09: pasadas 24 h se vuelve a pedir la lista; con ETag, un 304 conserva lo guardado")
	void t013_07_afterTtlRefetchesWithEtag() {
		FakeGithub.Account account = fake.account("cache").repo("uno", "Primero", false, List.of(), "Java", 10);
		Linked linked = linkNew(account);

		clock.advance(Duration.ofHours(25));
		fake.reset();
		JsonNode first = repositories(linked.api());
		clock.advance(Duration.ofHours(25));
		JsonNode second = repositories(linked.api());

		assertThat(names(first)).containsExactly("uno");
		assertThat(names(second)).containsExactly("uno");
		List<FakeGithub.Request> lists = fake.apiRequests().stream().filter(r -> r.path().endsWith("/repos")).toList();
		assertThat(lists).hasSize(2);
		assertThat(lists.get(0).header("If-None-Match")).isNull();
		assertThat(lists.get(1).header("If-None-Match")).startsWith("W/");
		assertThat(second.get("stale").asBoolean()).isFalse();
	}

	// ------------------------------------------------------------------------------------------ 013-08

	@Test
	@DisplayName("013-08: elegir lee los lenguajes solo de los elegidos y el código calcula el porcentaje por bytes (1 decimal, de mayor a menor)")
	void t013_08_selectionFetchesLanguagesOfChosenOnly() {
		FakeGithub.Account account = fake.account("seleccion")
				.repo("elegido", "Con lenguajes", false, List.of(), "TypeScript", 2000, "CSS", 1000)
				.repo("otro", "Sin elegir", false, List.of(), "Java", 50);
		Linked linked = linkNew(account);
		JsonNode list = repositories(linked.api());
		fake.reset();

		Response response = select(linked.api(), List.of(repoId(list, "elegido")));

		assertThat(response.status()).isEqualTo(200);
		JsonNode chosen = item(response.json(), "elegido");
		assertThat(chosen.get("selected").asBoolean()).isTrue();
		assertThat(chosen.get("languages").get(0).get("name").asString()).isEqualTo("TypeScript");
		assertThat(chosen.get("languages").get(0).get("percent").decimalValue()).isEqualByComparingTo("66.7");
		assertThat(chosen.get("languages").get(1).get("percent").decimalValue()).isEqualByComparingTo("33.3");
		assertThat(item(response.json(), "otro").get("languages")).isEmpty();
		assertThat(fake.apiRequests()).extracting(FakeGithub.Request::path)
				.containsExactly("/repos/" + account.login + "/elegido/languages");
		// Volver a elegir el mismo no vuelve a llamar (los lenguajes siguen vigentes).
		fake.reset();
		assertThat(select(linked.api(), List.of(repoId(list, "elegido"))).status()).isEqualTo(200);
		assertThat(fake.requests()).isEmpty();
	}

	@Test
	@DisplayName("013-08: más de 6, ids repetidos, ajenos o inventados dan 422; 0 es válido")
	void t013_08_invalidSelectionsAre422() {
		FakeGithub.Account account = fake.account("limites");
		for (int i = 1; i <= 7; i++) {
			account.repo("r" + i, "Repositorio " + i, false, List.of(), "Java", 10);
		}
		Linked linked = linkNew(account);
		Linked other = linkNew(fake.account("ajeno").repo("suyo", "De otra persona", false, List.of(), "Java", 10));
		JsonNode list = repositories(linked.api());
		List<String> seven = new ArrayList<>();
		for (int i = 1; i <= 7; i++) {
			seven.add(repoId(list, "r" + i));
		}
		String foreign = repoId(repositories(other.api()), "suyo");

		assertThat(errors(select(linked.api(), seven))).containsExactly("repoIds:max-items");
		assertThat(errors(select(linked.api(), List.of(seven.get(0), seven.get(0))))).containsExactly("repoIds[1]:duplicate");
		assertThat(errors(select(linked.api(), List.of(foreign)))).containsExactly("repoIds[0]:unknown-repository");
		assertThat(errors(select(linked.api(), List.of("no-es-un-id")))).containsExactly("repoIds[0]:unknown-repository");
		assertThat(errors(linked.api().put(GITHUB + "/repositories/selection", body("otra", 1))))
				.containsExactly("repoIds:required");
		Response none = select(linked.api(), List.of());
		assertThat(none.status()).isEqualTo(200);
		assertThat(none.json().get("items")).allSatisfy(item -> assertThat(item.get("selected").asBoolean()).isFalse());
	}

	@Test
	@DisplayName("013-07 y 013-08: sin vínculo, repositorios, selección, actualizar y sugerencias dan 409 not-verified")
	void t013_07_withoutLinkIs409() {
		ApiClient api = student();

		for (Response response : List.of(api.get(GITHUB + "/repositories"), select(api, List.of()),
				api.post(GITHUB + "/refresh", null), api.get(GITHUB + "/suggestions"),
				decide(api, decision("java", "ACCEPT", 2)))) {
			assertThat(response.status()).isEqualTo(409);
			assertThat(response.json().get("code").asString()).isEqualTo("not-verified");
		}
		assertThat(api.get(GITHUB).status()).isEqualTo(204);
	}

	// ------------------------------------------------------------------------------------------ 013-09 y 013-05

	@Test
	@DisplayName("013-09: actualizar usa If-None-Match, quita de la selección lo que desapareció (removed[]) y no se repite antes de 10 minutos (429)")
	void t013_09_refreshWithEtagRemovedAndInterval() {
		FakeGithub.Account account = fake.account("actualizar")
				.repo("queda", "Sigue", false, List.of(), "Java", 10)
				.repo("se-va", "Pasará a privado", false, List.of(), "Python", 10);
		Linked linked = linkNew(account);
		JsonNode list = repositories(linked.api());
		assertThat(select(linked.api(), List.of(repoId(list, "queda"), repoId(list, "se-va"))).status()).isEqualTo(200);
		account.removeRepo("se-va");
		fake.reset();

		Response refreshed = linked.api().post(GITHUB + "/refresh", null);

		assertThat(refreshed.status()).as(refreshed.body()).isEqualTo(200);
		assertThat(refreshed.json().get("removed")).extracting(node -> node.get("name").asString()).containsExactly("se-va");
		assertThat(names(refreshed.json())).containsExactly("queda");
		assertThat(item(refreshed.json(), "queda").get("selected").asBoolean()).isTrue();
		assertThat(fake.apiRequests()).filteredOn(r -> r.path().endsWith("/languages")).singleElement()
				.satisfies(r -> assertThat(r.header("If-None-Match")).startsWith("W/"));

		Response tooSoon = linked.api().post(GITHUB + "/refresh", null);
		assertThat(tooSoon.status()).isEqualTo(429);
		assertThat(tooSoon.header("Retry-After")).isNotNull();

		clock.advance(Duration.ofMinutes(11));
		fake.reset();
		Response later = linked.api().post(GITHUB + "/refresh", null);
		assertThat(later.status()).isEqualTo(200);
		assertThat(later.json().get("removed")).isEmpty();
		// Sin cambios en GitHub: la lista responde 304 y se conserva lo guardado.
		assertThat(fake.apiRequests()).filteredOn(r -> r.path().endsWith("/repos")).singleElement()
				.satisfies(r -> assertThat(r.header("If-None-Match")).startsWith("W/"));
		assertThat(names(later.json())).containsExactly("queda");
	}

	@Test
	@DisplayName("013-05: si la persona renombra su cuenta, al actualizar se guarda el login nuevo (manda el id numérico)")
	void t013_05_renameUpdatesLogin() {
		FakeGithub.Account account = fake.account("antes").repo("app", "App", false, List.of(), "Java", 10);
		Linked linked = linkNew(account);
		String newLogin = "despues-" + account.id;
		fake.rename(account, newLogin);

		Response refreshed = linked.api().post(GITHUB + "/refresh", null);

		assertThat(refreshed.status()).as(refreshed.body()).isEqualTo(200);
		assertThat(linked.api().get(GITHUB).json().get("login").asString()).isEqualTo(newLogin);
		assertThat(jdbc.queryForObject("SELECT github_user_id FROM github_link WHERE user_id = ?", Long.class,
				linked.student().id())).isEqualTo(account.id);
		assertThat(item(refreshed.json(), "app").get("url").asString()).contains(newLogin);
	}

	// ------------------------------------------------------------------------------------------ 013-20

	@Test
	@DisplayName("013-20: GitHub caído con lista guardada devuelve lo guardado con stale: true; una respuesta malformada tampoco rompe lo guardado")
	void t013_20_downWithCacheIsStale() {
		FakeGithub.Account account = fake.account("caido-cache").repo("guardado", "Ya leído", false, List.of(), "Java", 10);
		Linked linked = linkNew(account);

		clock.advance(Duration.ofHours(25));
		fake.fail("/users/", 500, "{}", Map.of());
		JsonNode down = repositories(linked.api());
		fake.fail("/users/", 200, "{\"no\":\"es una lista\"}", Map.of());
		JsonNode malformed = repositories(linked.api());

		assertThat(down.get("stale").asBoolean()).isTrue();
		assertThat(names(down)).containsExactly("guardado");
		assertThat(malformed.get("stale").asBoolean()).isTrue();
		assertThat(names(malformed)).containsExactly("guardado");
	}

	@Test
	@DisplayName("013-20: GitHub caído sin nada guardado da 503 github-unavailable con retryable: true (nunca 500); acciones también 503")
	void t013_20_downWithoutCacheIs503() {
		FakeGithub.Account account = fake.account("caido-vacio").repo("a", "A", false, List.of(), "Java", 10);
		// La primera lista falla al vincular: la cuenta queda comprobada, sin lista guardada.
		fake.fail("/users/", 502, "", Map.of());
		Linked linked = linkNew(account);

		Response response = linked.api().get(GITHUB + "/repositories");

		assertThat(response.status()).isEqualTo(503);
		assertThat(response.json().get("code").asString()).isEqualTo("github-unavailable");
		assertThat(response.json().get("retryable").asBoolean()).isTrue();
		assertThat(response.json().get("detail").asString()).isNotBlank();
		Response refresh = linked.api().post(GITHUB + "/refresh", null);
		assertThat(refresh.status()).isEqualTo(503);
		assertThat(refresh.json().get("code").asString()).isEqualTo("github-unavailable");

		fake.clearFailure("/users/");
		assertThat(names(repositories(linked.api()))).containsExactly("a");
	}

	@Test
	@DisplayName("013-20: si GitHub cae al leer lenguajes, elegir da 503 y no guarda una selección a medias")
	void t013_20_selectionFailsWhole() {
		FakeGithub.Account account = fake.account("lenguajes-caidos").repo("x", "X", false, List.of(), "Java", 10);
		Linked linked = linkNew(account);
		String id = repoId(repositories(linked.api()), "x");
		fake.fail("/repos/", 504, "", Map.of());

		Response response = select(linked.api(), List.of(id));

		assertThat(response.status()).isEqualTo(503);
		assertThat(response.json().get("code").asString()).isEqualTo("github-unavailable");
		assertThat(item(repositories(linked.api()), "x").get("selected").asBoolean()).isFalse();
	}

	// ------------------------------------------------------------------------------------------ 013-16 y 013-18

	@Test
	@DisplayName("013-16: GithubShowcase.forUser da el login comprobado y solo los repositorios elegidos; vacío sin vínculo")
	void t013_16_showcase() {
		FakeGithub.Account account = fake.account("vitrina")
				.repo("mostrar", "Se muestra", false, List.of("docker"), "Java", 30, "Dockerfile", 10)
				.repo("oculto", "No elegido", false, List.of(), "Go", 10);
		Linked linked = linkNew(account);
		assertThat(select(linked.api(), List.of(repoId(repositories(linked.api()), "mostrar"))).status()).isEqualTo(200);

		Optional<GithubShowcase.Showcase> result = showcase.forUser(linked.student().id());

		assertThat(result).isPresent();
		assertThat(result.get().login()).isEqualTo(account.login);
		assertThat(result.get().repositories()).singleElement().satisfies(repo -> {
			assertThat(repo.name()).isEqualTo("mostrar");
			assertThat(repo.url()).isEqualTo("https://github.com/" + account.login + "/mostrar");
			assertThat(repo.languages()).extracting(GithubShowcase.Language::name).containsExactly("Java", "Dockerfile");
			assertThat(repo.topics()).containsExactly("docker");
		});
		assertThat(showcase.forUser(registerStudent().id())).isEmpty();
	}

	@Test
	@DisplayName("013-18: cada llamada lleva Accept, X-GitHub-Api-Version y User-Agent; tiempos de 3 s y 5 s por defecto")
	void t013_18_headersAndTimeouts() {
		Linked linked = linkNew(fake.account("cabeceras").repo("r", "R", false, List.of(), "Java", 10));
		fake.reset();
		select(linked.api(), List.of(repoId(repositories(linked.api()), "r")));

		assertThat(fake.apiRequests()).isNotEmpty().allSatisfy(request -> {
			assertThat(request.header("Accept")).isEqualTo("application/vnd.github+json");
			assertThat(request.header("X-GitHub-Api-Version")).isEqualTo("2022-11-28");
			assertThat(request.header("User-Agent")).isEqualTo("Talenti");
			// Sin clave de servidor configurada, la API pública va sin autenticación (013-21).
			assertThat(request.header("Authorization")).isNull();
		});
		assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
		assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
	}

}
