package com.talenti.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;
import com.talenti.support.FakeGithub;

import tools.jackson.databind.JsonNode;

/** Habilidades observadas: detección, sugerencias, decisiones, evidencia y compatibilidad (013-10 a 013-15). */
class GithubSuggestionsTests extends GithubIntegrationTest {

	/** "Practicante Backend Java" de los datos de ejemplo. */
	private static final String BACKEND_JAVA = "d0000000-0000-4000-8000-000000000001";

	/** Vincula una cuenta con dos repositorios y elige solo el primero. */
	private Linked linkedWithSelection() {
		FakeGithub.Account account = fake.account("sugerencias")
				.repo("tienda", "Tienda", false, List.of("spring-boot", "cosa-rara"), "Java", 7000, "PLpgSQL", 2000,
						"Dockerfile", 950, "Shell", 50)
				.repo("no-elegido", "Otro", false, List.of("react"), "TypeScript", 1000);
		Linked linked = linkNew(account);
		JsonNode list = repositories(linked.api());
		assertThat(select(linked.api(), List.of(repoId(list, "tienda"))).status()).isEqualTo(200);
		return linked;
	}

	private static List<String> slugs(JsonNode suggestions) {
		List<String> slugs = new ArrayList<>();
		suggestions.get("suggestions").forEach(s -> slugs.add(s.get("slug").asString()));
		return slugs;
	}

	private static JsonNode suggestion(JsonNode suggestions, String slug) {
		for (JsonNode s : suggestions.get("suggestions")) {
			if (s.get("slug").asString().equals(slug)) {
				return s;
			}
		}
		throw new AssertionError("No está la sugerencia " + slug);
	}

	private static JsonNode profileSkill(JsonNode profile, String slug) {
		for (JsonNode skill : profile.get("skills")) {
			if (skill.get("slug").asString().equals(slug)) {
				return skill;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------ 013-10 y 013-11

	@Test
	@DisplayName("013-10 y 013-11: solo de los elegidos; lenguajes con al menos 10 % y temas, con el mapa y los sinónimos; lo que no está en el catálogo queda unmatched; fuente RULES")
	void t013_10_detectionFromSelectedRepos() {
		Linked linked = linkedWithSelection();

		JsonNode body = ok(linked.api().get(GITHUB + "/suggestions"));

		assertThat(body.get("source").asString()).isEqualTo("RULES");
		// Java 70 %, PLpgSQL 20 % (mapa: postgresql), Dockerfile 9,5 % (por debajo del 10 %), Shell 0,5 %; temas
		// spring-boot (catálogo) y cosa-rara (no está). "react" es del repositorio no elegido.
		assertThat(slugs(body)).containsExactlyInAnyOrder("java", "postgresql", "spring-boot");
		JsonNode java = suggestion(body, "java");
		assertThat(java.get("name").asString()).isEqualTo("Java");
		assertThat(java.get("evidence")).singleElement().satisfies(e -> {
			assertThat(e.get("repo").asString()).isEqualTo("tienda");
			assertThat(e.get("source").asString()).isEqualTo("LANGUAGE");
			assertThat(e.get("share").decimalValue()).isEqualByComparingTo("70.0");
		});
		assertThat(suggestion(body, "spring-boot").get("evidence")).singleElement().satisfies(e -> {
			assertThat(e.get("source").asString()).isEqualTo("TOPIC");
			assertThat(e.has("share")).isFalse();
		});
		assertThat(java.get("currentLevel").isNull()).isTrue();
		assertThat(body.get("unmatched")).extracting(u -> u.get("name").asString()).containsExactly("cosa-rara");
		// Mirar las sugerencias no cambia el perfil (nada entra sin confirmar, T2).
		assertThat(ok(linked.api().get(PROFILE)).get("skills")).isEmpty();
	}

	@Test
	@DisplayName("013-11: cada sugerencia trae el nivel y la evidencia actuales si la habilidad ya estaba en el perfil")
	void t013_11_currentLevelAndEvidence() {
		Linked linked = linkedWithSelection();
		ok(linked.api().put(PROFILE + "/skills", skills(skill("java", 3))));

		JsonNode java = suggestion(ok(linked.api().get(GITHUB + "/suggestions")), "java");

		assertThat(java.get("currentLevel").asInt()).isEqualTo(3);
		assertThat(java.get("currentEvidence").asString()).isEqualTo("DECLARED");
	}

	// ------------------------------------------------------------------------------------------ 013-12

	@Test
	@DisplayName("013-12: aceptar guarda la habilidad como OBSERVED con el nivel que elige la persona; rechazar la recuerda y no vuelve a sugerirse")
	void t013_12_acceptAndReject() {
		Linked linked = linkedWithSelection();
		ok(linked.api().put(PROFILE + "/skills", skills(skill("java", 3))));

		Response response = decide(linked.api(), decision("postgresql", "ACCEPT", 2), decision("java", "ACCEPT", null),
				decision("spring-boot", "REJECT", null));

		JsonNode profile = ok(response);
		assertThat(profileSkill(profile, "postgresql").get("evidence").asString()).isEqualTo("OBSERVED");
		assertThat(profileSkill(profile, "postgresql").get("level").asInt()).isEqualTo(2);
		// Ya estaba: conserva su nivel (no lo envió) y pasa a OBSERVED.
		assertThat(profileSkill(profile, "java").get("level").asInt()).isEqualTo(3);
		assertThat(profileSkill(profile, "java").get("evidence").asString()).isEqualTo("OBSERVED");
		assertThat(profileSkill(profile, "spring-boot")).isNull();
		assertThat(profile.get("completeness")).isNotNull();
		// Ni las confirmadas como OBSERVED ni las rechazadas se vuelven a sugerir.
		assertThat(slugs(ok(linked.api().get(GITHUB + "/suggestions")))).isEmpty();
		assertThat(jdbc.queryForObject("SELECT array_to_string(dismissed_slugs, ',') FROM github_link WHERE user_id = ?",
				String.class, linked.student().id())).isEqualTo("spring-boot");
	}

	@Test
	@DisplayName("013-12: el nivel es obligatorio si la habilidad no estaba; slug no sugerido, decisión inválida o nivel fuera de 1 a 3 dan 422 y no cambia nada")
	void t013_12_invalidDecisionsAre422() {
		Linked linked = linkedWithSelection();

		assertThat(errors(decide(linked.api(), decision("postgresql", "ACCEPT", null))))
				.containsExactly("items[0].level:invalid-level");
		assertThat(errors(decide(linked.api(), decision("react", "ACCEPT", 2))))
				.containsExactly("items[0].slug:not-suggested");
		assertThat(errors(decide(linked.api(), decision("java", "QUIZAS", 2))))
				.containsExactly("items[0].decision:invalid-decision");
		assertThat(errors(decide(linked.api(), decision("java", "ACCEPT", 4), decision("postgresql", "ACCEPT", "2"))))
				.containsExactly("items[0].level:invalid-level", "items[1].level:invalid-level");
		assertThat(errors(decide(linked.api(), decision("java", "ACCEPT", 1), decision("java", "REJECT", null))))
				.containsExactly("items[1].slug:duplicate");
		assertThat(errors(linked.api().post(GITHUB + "/suggestions/decisions", body("items", List.of()))))
				.containsExactly("items:required");
		// Un 422 no deja nada a medias.
		assertThat(ok(linked.api().get(PROFILE)).get("skills")).isEmpty();
	}

	// ------------------------------------------------------------------------------------------ 013-13

	@Test
	@DisplayName("013-13: editar el nivel en /profile/skills conserva OBSERVED; las nuevas nacen DECLARED; la compatibilidad muestra la fuente real")
	void t013_13_evidenceSurvivesEditsAndShowsInCompatibility() {
		Linked linked = linkedWithSelection();
		ok(decide(linked.api(), decision("java", "ACCEPT", 1)));

		JsonNode profile = ok(linked.api().put(PROFILE + "/skills", skills(skill("java", 2), skill("git", 1))));

		assertThat(profileSkill(profile, "java").get("level").asInt()).isEqualTo(2);
		assertThat(profileSkill(profile, "java").get("evidence").asString()).isEqualTo("OBSERVED");
		assertThat(profileSkill(profile, "git").get("evidence").asString()).isEqualTo("DECLARED");
		JsonNode compatibility = ok(linked.api().get("/api/v1/compatibility/vacancies/" + BACKEND_JAVA));
		List<String> evidences = new ArrayList<>();
		for (String list : List.of("matches", "gaps")) {
			for (JsonNode item : compatibility.get(list)) {
				if ("REQUIREMENT".equals(item.get("type").asString())) {
					evidences.add(item.get("skill").get("slug").asString() + ":" + item.get("evidence").asString());
				}
			}
		}
		assertThat(evidences).contains("java:OBSERVED", "git:DECLARED");
	}

	// ------------------------------------------------------------------------------------------ 013-14

	@Test
	@DisplayName("013-14: vincular sin confirmar no cambia el porcentaje, y una habilidad OBSERVED cuenta igual que una declarada del mismo nivel")
	void t013_14_githubNeverAddsPoints() {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		ok(api.put(PROFILE + "/skills", skills(skill("java", 2), skill("spring-boot", 1), skill("sql", 1), skill("git", 1))));
		ok(api.put(PROFILE + "/availability", body("hoursPerWeek", 20, "acceptedModalities", List.of("REMOTE"),
				"location", "Lima")));
		int before = ok(api.get("/api/v1/compatibility/vacancies/" + BACKEND_JAVA)).get("percent").asInt();

		FakeGithub.Account account = fake.account("sin-puntos").repo("estrellas", "Muy popular", false,
				List.of("docker", "postgresql", "rest-api"), "Java", 5000, "Dockerfile", 4000);
		account.repos.get(0).put("stargazers_count", 50000);
		String state = start(api);
		assertThat(outcome(callback(api, fake.authorize(account), state))).isEqualTo("linked");
		assertThat(select(api, List.of(repoId(repositories(api), "estrellas"))).status()).isEqualTo(200);
		int linkedWithoutConfirming = ok(api.get("/api/v1/compatibility/vacancies/" + BACKEND_JAVA)).get("percent").asInt();

		ok(decide(api, decision("java", "ACCEPT", null)));
		int observedSameLevel = ok(api.get("/api/v1/compatibility/vacancies/" + BACKEND_JAVA)).get("percent").asInt();

		assertThat(linkedWithoutConfirming).isEqualTo(before);
		assertThat(observedSameLevel).isEqualTo(before);
	}

	// ------------------------------------------------------------------------------------------ 013-15

	@Test
	@DisplayName("013-15: desvincular borra vínculo, repositorios, rechazos y state pendiente, se audita y da 204; lo confirmado queda DECLARED")
	void t013_15_unlinkRemovesEverythingButKeepsSkillsDeclared() {
		Linked linked = linkedWithSelection();
		ok(decide(linked.api(), decision("java", "ACCEPT", 2), decision("spring-boot", "REJECT", null)));
		java.util.UUID id = linked.student().id();
		jdbc.update("INSERT INTO github_oauth_state (state_hash, user_id, session_ref, expires_at) VALUES (?, ?, 'x', now() + interval '5 minutes')",
				"f".repeat(64), id);

		Response response = linked.api().delete(GITHUB);

		assertThat(response.status()).isEqualTo(204);
		assertThat(count("SELECT count(*) FROM github_link WHERE user_id = ?", id)).isZero();
		assertThat(count("SELECT count(*) FROM github_repository WHERE user_id = ?", id)).isZero();
		assertThat(count("SELECT count(*) FROM github_oauth_state WHERE user_id = ?", id)).isZero();
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'GITHUB_UNLINKED' AND actor_user_id = ?", id))
				.isEqualTo(1);
		JsonNode java = profileSkill(ok(linked.api().get(PROFILE)), "java");
		assertThat(java.get("evidence").asString()).isEqualTo("DECLARED");
		assertThat(java.get("level").asInt()).isEqualTo(2);
		assertThat(linked.api().get(GITHUB).status()).isEqualTo(204);
		// Desvincular nunca se limita: repetirlo también da 204.
		assertThat(linked.api().delete(GITHUB).status()).isEqualTo(204);
		// Y se puede volver a vincular la misma cuenta.
		assertThat(outcome(callback(linked.api(), fake.authorize(linked.account()), start(linked.api()))))
				.isEqualTo("linked");
	}

	@Test
	@DisplayName("013-15: borrar la cuenta borra en cascada todo lo de GitHub (RN-12)")
	void t013_15_accountDeletionCascades() {
		Linked linked = linkedWithSelection();
		java.util.UUID id = linked.student().id();
		jdbc.update("INSERT INTO github_oauth_state (state_hash, user_id, session_ref, expires_at) VALUES (?, ?, 'x', now() + interval '5 minutes')",
				"e".repeat(64), id);
		assertThat(count("SELECT count(*) FROM github_repository WHERE user_id = ?", id)).isEqualTo(2);

		jdbc.update("DELETE FROM app_user WHERE id = ?", id);

		assertThat(count("SELECT count(*) FROM github_link WHERE user_id = ?", id)).isZero();
		assertThat(count("SELECT count(*) FROM github_repository WHERE user_id = ?", id)).isZero();
		assertThat(count("SELECT count(*) FROM github_oauth_state WHERE user_id = ?", id)).isZero();
	}

}
