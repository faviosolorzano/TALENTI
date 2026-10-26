package com.talenti.github.client;

import java.time.Instant;
import java.util.List;

/**
 * Un repositorio público tal como lo devuelve GitHub, reducido a lo que la spec permite guardar (013-06): ni
 * estrellas, ni seguidores, ni número de commits. La descripción ya viene recortada a 350 caracteres.
 */
public record RepoData(long id, String name, String description, String htmlUrl, String primaryLanguage,
		List<String> topics, boolean fork, boolean archived, Instant pushedAt) {

	public RepoData {
		topics = List.copyOf(topics);
	}

	@Override
	public String toString() {
		return "RepoData[id=" + id + "]";
	}

}
