package com.talenti.github.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.talenti.github.domain.LanguageShare;
import com.talenti.github.persistence.GithubStore.RepoRow;

/**
 * Cuerpos de las respuestas de {@code /github} (spec 013, sección 6). Llevan contenido de GitHub (descripciones,
 * temas): ningún {@code toString} lo imprime (013-17).
 */
public final class Views {

	private Views() {
	}

	/** {@code Link}: {@code {status, login, githubUserId, verifiedAt}}. */
	public record Link(String status, String login, long githubUserId, Instant verifiedAt) {

		@Override
		public String toString() {
			return "Link[status=" + status + "]";
		}

	}

	public record AuthorizeUrl(String authorizeUrl) {

		@Override
		public String toString() {
			return "AuthorizeUrl[...]";
		}

	}

	public record Language(String name, BigDecimal percent) {
	}

	/** {@code {id, name, description, url, primaryLanguage, languages[{name, percent}], topics[], archived, pushedAt, selected}}. */
	public record Repository(UUID id, String name, String description, String url, String primaryLanguage,
			List<Language> languages, List<String> topics, boolean archived, Instant pushedAt, boolean selected) {

		static Repository of(RepoRow row) {
			List<Language> languages = row.languages() == null ? List.of()
					: row.languages().stream().map(Views::language).toList();
			return new Repository(row.id(), row.name(), row.description(), row.htmlUrl(), row.primaryLanguage(),
					languages, row.topics(), row.archived(), row.pushedAt(), row.selected());
		}

		@Override
		public String toString() {
			return "Repository[id=" + id + "]";
		}

	}

	private static Language language(LanguageShare share) {
		return new Language(share.name(), share.percent());
	}

	public record Repositories(List<Repository> items, Instant fetchedAt, boolean stale) {

		@Override
		public String toString() {
			return "Repositories[items=" + items.size() + ", stale=" + stale + "]";
		}

	}

	public record Selection(List<Repository> items) {

		@Override
		public String toString() {
			return "Selection[items=" + items.size() + "]";
		}

	}

	/** Un repositorio elegido que desapareció o pasó a privado (013-09). */
	public record Removed(UUID id, String name) {

		@Override
		public String toString() {
			return "Removed[id=" + id + "]";
		}

	}

	public record Refresh(List<Repository> items, List<Removed> removed, Instant fetchedAt) {

		@Override
		public String toString() {
			return "Refresh[items=" + items.size() + ", removed=" + removed.size() + "]";
		}

	}

	/** {@code {repo, source: LANGUAGE|TOPIC, share?}}. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record Evidence(String repo, String source, BigDecimal share) {

		@Override
		public String toString() {
			return "Evidence[source=" + source + "]";
		}

	}

	/** {@code {slug, name, evidence[], currentLevel?, currentEvidence?}}. */
	public record Suggestion(String slug, String name, List<Evidence> evidence, Integer currentLevel,
			String currentEvidence) {

		@Override
		public String toString() {
			return "Suggestion[slug=" + slug + "]";
		}

	}

	public record Unmatched(String name, List<Evidence> evidence) {

		@Override
		public String toString() {
			return "Unmatched[...]";
		}

	}

	public record Suggestions(List<Suggestion> suggestions, List<Unmatched> unmatched, String source) {

		@Override
		public String toString() {
			return "Suggestions[suggestions=" + suggestions.size() + ", unmatched=" + unmatched.size() + "]";
		}

	}

	/**
	 * Una decisión sobre una sugerencia: {@code {slug, decision: ACCEPT|REJECT, level?}}. {@code levelMalformed}: el
	 * nivel vino pero no es un entero.
	 */
	public record Decision(String slug, String decision, Integer level, boolean levelMalformed) {

		@Override
		public String toString() {
			return "Decision[...]";
		}

	}

}
