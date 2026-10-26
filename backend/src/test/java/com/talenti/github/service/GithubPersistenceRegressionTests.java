package com.talenti.github.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.talenti.github.client.*;
import com.talenti.github.config.GithubProperties;
import com.talenti.github.persistence.GithubStore;
import com.talenti.profile.SkillEvidenceWriter;
import com.talenti.shared.audit.AuditRecorder;
import com.talenti.vacancy.SkillCatalog;
import tools.jackson.databind.json.JsonMapper;

/** Servicio y almacenamiento reales; GitHub simulado; PostgreSQL temporal indicado explícitamente. */
@EnabledIfSystemProperty(named = "talenti.test.jdbc-url", matches = "jdbc:postgresql:.*")
class GithubPersistenceRegressionTests {

	private JdbcTemplate admin, jdbc;
	private String schema;
	private AnnotationConfigApplicationContext context;
	private GithubStore store;
	private GithubService service;
	private final GithubClient client = mock(GithubClient.class);
	private final UUID user = UUID.randomUUID();
	private final Instant now = Instant.parse("2026-10-26T15:00:00Z");

	@BeforeEach
	void setup() throws Exception {
		String url = System.getProperty("talenti.test.jdbc-url");
		String username = System.getProperty("talenti.test.jdbc-user", "review_test");
		admin = new JdbcTemplate(new DriverManagerDataSource(url, username, ""));
		schema = "test_github_" + UUID.randomUUID().toString().replace("-", "");
		admin.execute("CREATE SCHEMA " + schema);
		var ds = new DriverManagerDataSource(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema, username, "");
		jdbc = new JdbcTemplate(ds);
		for (String migration : List.of("V2__identity_and_audit.sql", "V10__github_link_and_repositories.sql")) {
			jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration", migration)));
		}
		jdbc.update("INSERT INTO app_user(id,email,password_hash,role) VALUES (?,?,'x','STUDENT')", user,
				user + "@ejemplo.test");
		context = new AnnotationConfigApplicationContext();
		context.registerBean(JdbcClient.class, () -> JdbcClient.create(ds));
		context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
		context.register(GithubStore.class);
		context.refresh();
		store = context.getBean(GithubStore.class);
		store.insertLink(user, "old-name", 123, now.minusSeconds(90000));
		store.markReposFetched(user, "old-etag", now.minusSeconds(90000));
		var config = new GithubProperties("http://127.0.0.1", "2022-11-28", "", null, false, 1, 6, 10,
				Duration.ofHours(24), Duration.ofMinutes(10), 5, Duration.ofSeconds(1), Duration.ofSeconds(1),
				30, ZoneId.of("America/Lima"), "");
		service = new GithubService(config, store, client, mock(SkillCatalog.class), mock(SkillEvidenceWriter.class),
				null, mock(AuditRecorder.class), Clock.fixed(now, ZoneOffset.UTC), new DataSourceTransactionManager(ds));
		when(client.userById(123)).thenReturn(new GithubUser(123, "new-name", "User"));
	}

	@AfterEach
	void cleanup() {
		if (context != null) context.close();
		if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE");
	}

	private RepoData repo(String name) {
		return new RepoData(999, name, null, "https://github.com/new-name/" + name, "Java", List.of(), false, false, now);
	}

	@Test
	void t013_09_renamePreservesActualStoredIdAndSelection() {
		store.syncRepos(user, List.of(repo("old-project")));
		UUID id = store.findRepos(user).getFirst().id();
		store.setSelection(user, List.of(id));
		when(client.repositories("new-name", 123, null, null)).thenReturn(new Fetched<>(List.of(repo("new-project")), "new-etag", false));
		when(client.languages("new-name", "new-project", null)).thenReturn(new Fetched<>(Map.of("Java", 100L), "language-etag", false));
		assertThat(service.refresh(user).removed()).isEmpty();
		assertThat(store.findRepos(user)).singleElement().satisfies(saved -> {
			assertThat(saved.id()).isEqualTo(id);
			assertThat(saved.selected()).isTrue();
			assertThat(saved.name()).isEqualTo("new-project");
			assertThat(saved.languages()).singleElement().satisfies(language -> assertThat(language.name()).isEqualTo("Java"));
		});
		assertThat(store.findLink(user).orElseThrow().login()).isEqualTo("new-name");
	}

	@Test
	void t013_05_failedIdentityDoesNotOverwriteActualCacheOrLink() {
		store.syncRepos(user, List.of(repo("saved-project")));
		when(client.userById(123)).thenThrow(new GithubNotFoundException());
		assertThat(service.repositories(user).stale()).isTrue();
		assertThat(store.findLink(user).orElseThrow().login()).isEqualTo("old-name");
		assertThat(store.findRepos(user)).singleElement().satisfies(repo -> assertThat(repo.name()).isEqualTo("saved-project"));
		verify(client, never()).repositories(anyString(), anyLong(), any(), any());
	}
}
