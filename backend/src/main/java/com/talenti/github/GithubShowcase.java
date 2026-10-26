package com.talenti.github;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Interfaz pública de github para la futura cara de empresa y el perfil (013-16): la cuenta comprobada y los
 * repositorios que la persona eligió mostrar. Solo vínculos {@code VERIFIED}; sin estrellas, seguidores ni commits.
 */
public interface GithubShowcase {

	/** Vacío si la persona no tiene GitHub vinculado. */
	Optional<Showcase> forUser(UUID userId);

	record Showcase(String login, List<Repository> repositories) {

		public Showcase {
			repositories = List.copyOf(repositories);
		}

		@Override
		public String toString() {
			return "Showcase[repositories=" + repositories.size() + "]";
		}

	}

	record Repository(String name, String description, String url, List<Language> languages, List<String> topics) {

		public Repository {
			languages = List.copyOf(languages);
			topics = List.copyOf(topics);
		}

		@Override
		public String toString() {
			return "Repository[...]";
		}

	}

	record Language(String name, BigDecimal percent) {
	}

}
