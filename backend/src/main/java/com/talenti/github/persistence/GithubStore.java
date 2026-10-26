package com.talenti.github.persistence;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.talenti.github.client.RepoData;
import com.talenti.github.domain.LanguageShare;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Acceso a las tres tablas del módulo github (github_link, github_oauth_state, github_repository). Es lo único que las
 * toca (013-16). Todas las consultas van parametrizadas y filtran por el dueño ({@code user_id}). Ninguna columna
 * guarda el token de GitHub (013-27).
 */
@Repository
public class GithubStore {

	private final JdbcClient jdbc;

	private final JsonMapper json;

	GithubStore(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	// ------------------------------------------------------------------------------------------ filas

	public record LinkRow(UUID userId, String login, long githubUserId, String status, Instant verifiedAt,
			String reposEtag, Instant reposFetchedAt, List<String> dismissedSlugs) {

		@Override
		public String toString() {
			return "LinkRow[userId=" + userId + ", githubUserId=" + githubUserId + "]";
		}

	}

	public record StateRow(UUID userId, String sessionRef, Instant expiresAt) {

		@Override
		public String toString() {
			return "StateRow[userId=" + userId + "]";
		}

	}

	/** @param languages nulo si aún no se leyeron sus lenguajes */
	public record RepoRow(UUID id, long githubRepoId, String name, String description, String htmlUrl,
			String primaryLanguage, List<String> topics, boolean fork, boolean archived, Instant pushedAt,
			boolean selected, List<LanguageShare> languages, String languagesEtag, Instant languagesFetchedAt) {

		@Override
		public String toString() {
			return "RepoRow[id=" + id + "]";
		}

	}

	// ------------------------------------------------------------------------------------------ vínculo

	public Optional<LinkRow> findLink(UUID userId) {
		return jdbc.sql("""
				SELECT user_id, login, github_user_id, status, verified_at, repos_etag, repos_fetched_at, dismissed_slugs
				FROM github_link WHERE user_id = :userId""")
				.param("userId", userId)
				.query((rs, row) -> new LinkRow(rs.getObject("user_id", UUID.class), rs.getString("login"),
						rs.getLong("github_user_id"), rs.getString("status"), instant(rs, "verified_at"),
						rs.getString("repos_etag"), instant(rs, "repos_fetched_at"), strings(rs.getArray("dismissed_slugs"))))
				.optional();
	}

	/**
	 * Crea el vínculo comprobado. Lanza {@link org.springframework.dao.DuplicateKeyException} si esa cuenta de GitHub
	 * ya está vinculada a otra persona (índice único, 013-28) o si la persona ya tiene un vínculo.
	 */
	public void insertLink(UUID userId, String login, long githubUserId, Instant verifiedAt) {
		jdbc.sql("""
				INSERT INTO github_link (user_id, login, github_user_id, status, verified_at, created_at, updated_at)
				VALUES (:userId, :login, :githubUserId, 'VERIFIED', :verifiedAt, :verifiedAt, :verifiedAt)""")
				.param("userId", userId).param("login", login).param("githubUserId", githubUserId)
				.param("verifiedAt", utc(verifiedAt)).update();
	}

	public void updateLogin(UUID userId, String login) {
		jdbc.sql("UPDATE github_link SET login = :login, updated_at = now() WHERE user_id = :userId")
				.param("login", login).param("userId", userId).update();
	}

	public void markReposFetched(UUID userId, String etag, Instant fetchedAt) {
		jdbc.sql("""
				UPDATE github_link SET repos_etag = :etag, repos_fetched_at = :fetchedAt, updated_at = now()
				WHERE user_id = :userId""")
				.param("etag", etag).param("fetchedAt", utc(fetchedAt)).param("userId", userId).update();
	}

	public void addDismissed(UUID userId, Collection<String> slugs) {
		if (slugs.isEmpty()) {
			return;
		}
		jdbc.sql("""
				UPDATE github_link SET dismissed_slugs = ARRAY(SELECT DISTINCT unnest(dismissed_slugs || CAST(:slugs AS text[]))),
				       updated_at = now()
				WHERE user_id = :userId""")
				.param("slugs", slugs.toArray(String[]::new)).param("userId", userId).update();
	}

	/** Borra todo lo traído de GitHub de la persona (013-15). @return si había un vínculo */
	public boolean deleteEverything(UUID userId) {
		jdbc.sql("DELETE FROM github_oauth_state WHERE user_id = :userId").param("userId", userId).update();
		jdbc.sql("DELETE FROM github_repository WHERE user_id = :userId").param("userId", userId).update();
		return jdbc.sql("DELETE FROM github_link WHERE user_id = :userId").param("userId", userId).update() > 0;
	}

	// ------------------------------------------------------------------------------------------ state (013-25)

	/** Guarda el hash del {@code state} nuevo y borra el anterior de esa persona (vale solo el último). */
	public void replaceState(UUID userId, String stateHash, String sessionRef, Instant expiresAt, Instant now) {
		jdbc.sql("DELETE FROM github_oauth_state WHERE user_id = :userId OR expires_at <= :now")
				.param("userId", userId).param("now", utc(now)).update();
		jdbc.sql("""
				INSERT INTO github_oauth_state (state_hash, user_id, session_ref, expires_at, created_at)
				VALUES (:hash, :userId, :sessionRef, :expiresAt, :now)""")
				.param("hash", stateHash).param("userId", userId).param("sessionRef", sessionRef)
				.param("expiresAt", utc(expiresAt)).param("now", utc(now)).update();
	}

	/** Consume el {@code state} (borrado atómico, 013-26): solo una petición puede recibir la fila. */
	public Optional<StateRow> consumeState(String stateHash) {
		return jdbc.sql("""
				DELETE FROM github_oauth_state WHERE state_hash = :hash
				RETURNING user_id, session_ref, expires_at""")
				.param("hash", stateHash)
				.query((rs, row) -> new StateRow(rs.getObject("user_id", UUID.class), rs.getString("session_ref"),
						instant(rs, "expires_at")))
				.optional();
	}

	public void deleteExpiredStates(Instant now) {
		jdbc.sql("DELETE FROM github_oauth_state WHERE expires_at <= :now").param("now", utc(now)).update();
	}

	// ------------------------------------------------------------------------------------------ repositorios

	public List<RepoRow> findRepos(UUID userId) {
		return jdbc.sql("""
				SELECT id, github_repo_id, name, description, html_url, primary_language, topics, is_fork, is_archived,
				       pushed_at, selected, languages, languages_etag, languages_fetched_at
				FROM github_repository WHERE user_id = :userId
				ORDER BY pushed_at DESC NULLS LAST, lower(name), id""")
				.param("userId", userId)
				.query((rs, row) -> new RepoRow(rs.getObject("id", UUID.class), rs.getLong("github_repo_id"),
						rs.getString("name"), rs.getString("description"), rs.getString("html_url"),
						rs.getString("primary_language"), strings(rs.getArray("topics")), rs.getBoolean("is_fork"),
						rs.getBoolean("is_archived"), instant(rs, "pushed_at"), rs.getBoolean("selected"),
						languages(rs.getString("languages")), rs.getString("languages_etag"),
						instant(rs, "languages_fetched_at")))
				.list();
	}

	/**
	 * Deja exactamente esta lista: agrega o actualiza por {@code github_repo_id} (conserva el id, la selección y los
	 * lenguajes) y borra los que ya no vienen (desaparecieron o pasaron a privados, 013-09).
	 */
	public void syncRepos(UUID userId, List<RepoData> repos) {
		String[] keep = repos.stream().map(repo -> Long.toString(repo.id())).toArray(String[]::new);
		jdbc.sql("DELETE FROM github_repository WHERE user_id = :userId AND NOT (github_repo_id = ANY (CAST(:keep AS bigint[])))")
				.param("userId", userId).param("keep", keep).update();
		for (RepoData repo : repos) {
			jdbc.sql("""
					INSERT INTO github_repository (user_id, github_repo_id, name, description, html_url, primary_language,
					                               topics, is_fork, is_archived, pushed_at)
					VALUES (:userId, :repoId, :name, :description, :url, :language, :topics, :fork, :archived, :pushedAt)
					ON CONFLICT (user_id, github_repo_id) DO UPDATE SET name = EXCLUDED.name,
					       description = EXCLUDED.description, html_url = EXCLUDED.html_url,
					       primary_language = EXCLUDED.primary_language, topics = EXCLUDED.topics,
					       is_fork = EXCLUDED.is_fork, is_archived = EXCLUDED.is_archived, pushed_at = EXCLUDED.pushed_at,
					       updated_at = now()""")
					.param("userId", userId).param("repoId", repo.id()).param("name", repo.name())
					.param("description", repo.description()).param("url", repo.htmlUrl())
					.param("language", repo.primaryLanguage()).param("topics", repo.topics().toArray(String[]::new))
					.param("fork", repo.fork()).param("archived", repo.archived())
					.param("pushedAt", repo.pushedAt() == null ? null : utc(repo.pushedAt())).update();
		}
	}

	/** Marca como elegidos exactamente estos repositorios de la persona (013-08). */
	public void setSelection(UUID userId, Collection<UUID> repoIds) {
		String[] ids = repoIds.stream().map(UUID::toString).toArray(String[]::new);
		jdbc.sql("""
				UPDATE github_repository SET selected = (id = ANY (CAST(:ids AS uuid[]))), updated_at = now()
				WHERE user_id = :userId""")
				.param("ids", ids).param("userId", userId).update();
	}

	public void saveLanguages(UUID userId, UUID repoId, List<LanguageShare> languages, String etag, Instant fetchedAt) {
		jdbc.sql("""
				UPDATE github_repository SET languages = CAST(:languages AS jsonb), languages_etag = :etag,
				       languages_fetched_at = :fetchedAt, updated_at = now()
				WHERE id = :id AND user_id = :userId""")
				.param("languages", toJson(languages)).param("etag", etag).param("fetchedAt", utc(fetchedAt))
				.param("id", repoId).param("userId", userId).update();
	}

	/** Un 304: lo guardado sigue vigente desde ahora. */
	public void touchLanguages(UUID userId, UUID repoId, Instant fetchedAt) {
		jdbc.sql("""
				UPDATE github_repository SET languages_fetched_at = :fetchedAt, updated_at = now()
				WHERE id = :id AND user_id = :userId""")
				.param("fetchedAt", utc(fetchedAt)).param("id", repoId).param("userId", userId).update();
	}

	public void deleteRepo(UUID userId, UUID repoId) {
		jdbc.sql("DELETE FROM github_repository WHERE id = :id AND user_id = :userId")
				.param("id", repoId).param("userId", userId).update();
	}

	// ------------------------------------------------------------------------------------------ piezas

	private String toJson(List<LanguageShare> languages) {
		ArrayNode array = json.createArrayNode();
		for (LanguageShare language : languages) {
			ObjectNode node = array.addObject();
			node.put("name", language.name());
			node.put("bytes", language.bytes());
			node.put("percent", language.percent());
		}
		return json.writeValueAsString(array);
	}

	private List<LanguageShare> languages(String value) {
		if (value == null) {
			return null;
		}
		List<LanguageShare> result = new ArrayList<>();
		for (JsonNode node : json.readTree(value)) {
			result.add(new LanguageShare(node.get("name").asString(), node.get("bytes").longValue(),
					node.get("percent").decimalValue().setScale(1, java.math.RoundingMode.HALF_UP)));
		}
		return List.copyOf(result);
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static List<String> strings(Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		try {
			return List.of((String[]) array.getArray());
		}
		finally {
			try {
				array.free();
			}
			catch (SQLException ignored) {
				// Liberar es opcional: el valor ya se leyó.
			}
		}
	}

}
