package com.talenti.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/** 006-02: {@code GET /api/v1/skills}, el catálogo público para el buscador de habilidades del perfil. */
class SkillCatalogEndpointTests extends VacancyIntegrationTest {

	private static final String SKILLS = "/api/v1/skills";

	@Autowired
	private SkillCatalog catalog;

	@Test
	@DisplayName("006-02: sin sesión responde 200 con todo el catálogo {slug, name, aliases[]} ordenado por nombre, sin Set-Cookie")
	void t006_02_publicCatalogOrderedByName() {
		Response response = client().get(SKILLS);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.setCookies()).isEmpty();
		JsonNode items = response.json();
		assertThat(items.isArray()).isTrue();
		assertThat(items).hasSize(catalog.all().size()).hasSizeGreaterThanOrEqualTo(30);
		List<String> names = new ArrayList<>();
		items.forEach(item -> names.add(item.get("name").asString()));
		// Mismo orden que el catálogo (ORDER BY name en la base de datos).
		assertThat(names).isEqualTo(catalog.all().stream().map(SkillInfo::name).toList());
		JsonNode javascript = null;
		for (JsonNode item : items) {
			if (item.get("slug").asString().equals("javascript")) {
				javascript = item;
			}
		}
		assertThat(javascript).isNotNull();
		assertThat(javascript.get("name").asString()).isEqualTo("JavaScript");
		assertThat(javascript.get("aliases")).extracting(JsonNode::asString).contains("JS");
		assertThat(new ArrayList<>(javascript.propertyNames())).containsExactlyInAnyOrder("slug", "name", "aliases");
	}

	@Test
	@DisplayName("006-02: Cache-Control public max-age=60 y ETag; con If-None-Match vigente responde 304 sin cuerpo")
	void t006_02_cacheAndEtag() {
		Response response = client().get(SKILLS);

		assertThat(response.header("Cache-Control")).isEqualTo("public, max-age=60");
		String etag = response.header("ETag");
		assertThat(etag).isNotBlank().startsWith("\"");

		Response notModified = client().get(SKILLS, Map.of("If-None-Match", etag));
		assertThat(notModified.status()).isEqualTo(304);
		assertThat(notModified.body()).isEmpty();
		assertThat(notModified.setCookies()).isEmpty();
		assertThat(notModified.header("Cache-Control")).isEqualTo("public, max-age=60");
		assertThat(client().get(SKILLS, Map.of("If-None-Match", "\"viejo\"")).status()).isEqualTo(200);
	}

	@Test
	@DisplayName("006-02: solo GET es público: POST, PUT y DELETE sin sesión dan 401")
	void t006_02_onlyGetIsPublic() {
		ApiClient visitor = client();

		assertThat(visitor.post(SKILLS, Map.of("slug", "x")).status()).isEqualTo(401);
		assertThat(visitor.put(SKILLS, Map.of("slug", "x")).status()).isEqualTo(401);
		assertThat(visitor.delete(SKILLS).status()).isEqualTo(401);
	}

}
