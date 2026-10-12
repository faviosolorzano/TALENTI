package com.talenti.vacancy.persistence;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.talenti.vacancy.SkillCatalog;
import com.talenti.vacancy.SkillInfo;
import com.talenti.vacancy.domain.TextNormalizer;

/** Catálogo de habilidades leído de la tabla {@code skill}. Interno de vacancy: los demás usan {@link SkillCatalog}. */
@Repository
class JdbcSkillCatalog implements SkillCatalog {

	private final JdbcClient jdbc;

	JdbcSkillCatalog(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<SkillInfo> all() {
		return jdbc.sql("SELECT id, slug, name, aliases FROM skill ORDER BY name").query((rs, row) -> {
			Array aliases = rs.getArray("aliases");
			try {
				String[] values = aliases == null ? new String[0] : (String[]) aliases.getArray();
				return new SkillInfo(rs.getObject("id", UUID.class), rs.getString("slug"), rs.getString("name"),
						Arrays.asList(values));
			}
			finally {
				if (aliases != null) {
					try {
						aliases.free();
					}
					catch (SQLException ignored) {
						// Liberar es opcional: el valor ya se leyó.
					}
				}
			}
		}).list();
	}

	@Override
	public Optional<SkillInfo> findBySlug(String slug) {
		return all().stream().filter(skill -> skill.slug().equals(slug)).findFirst();
	}

	@Override
	public Optional<SkillInfo> resolve(String text) {
		String wanted = TextNormalizer.normalize(text);
		if (wanted.isEmpty()) {
			return Optional.empty();
		}
		// El catálogo tiene unas decenas de filas: comparar en memoria evita una consulta por sinónimo.
		return all().stream()
				.filter(skill -> TextNormalizer.normalize(skill.name()).equals(wanted)
						|| skill.aliases().stream().anyMatch(alias -> TextNormalizer.normalize(alias).equals(wanted)))
				.findFirst();
	}

}
