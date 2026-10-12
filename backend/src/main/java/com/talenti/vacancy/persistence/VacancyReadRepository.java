package com.talenti.vacancy.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.talenti.vacancy.domain.CompanyDetail;
import com.talenti.vacancy.domain.CompanyRef;
import com.talenti.vacancy.domain.FeaturedCompany;
import com.talenti.vacancy.domain.FilterOptions;
import com.talenti.vacancy.domain.Modality;
import com.talenti.vacancy.domain.Requirement;
import com.talenti.vacancy.domain.RequirementKind;
import com.talenti.vacancy.domain.Requirements;
import com.talenti.vacancy.domain.SearchCriteria;
import com.talenti.vacancy.domain.SearchResult;
import com.talenti.vacancy.domain.SearchSort;
import com.talenti.vacancy.domain.SearchText;
import com.talenti.vacancy.domain.SkillRef;
import com.talenti.vacancy.domain.SkillTag;
import com.talenti.vacancy.domain.VacancyCard;
import com.talenti.vacancy.domain.VacancyDetail;
import com.talenti.vacancy.domain.VacancyStatus;

/**
 * Consultas de solo lectura del módulo vacancy. Todo valor del visitante viaja como parámetro (nunca se une al
 * texto de la consulta) y el texto de la búsqueda no se escribe en ningún registro.
 *
 * <p>La búsqueda es {@code unaccent} + {@code ILIKE} por términos (spec 004, duda 3): sin relevancia ni
 * {@code tsvector}. {@code unaccent} la crea el administrador de la base en {@code db/init/01-init-roles.sh}.
 */
@Repository
public class VacancyReadRepository {

	private static final String CARD_COLUMNS = """
			v.id, v.title, v.summary, v.modality, v.location, v.hours_per_week, v.published_at, v.featured,
			c.id AS company_id, c.name AS company_name, c.city AS company_city""";

	/** Obligatorias antes que deseables. */
	private static final String REQUIRED_FIRST = "CASE r.kind WHEN 'REQUIRED' THEN 0 ELSE 1 END";

	private final JdbcClient jdbc;

	VacancyReadRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Vacantes abiertas que cumplen el filtro, en la página pedida ({@code page} desde 1). */
	public SearchResult search(SearchCriteria criteria, int page, int size) {
		Map<String, Object> params = new LinkedHashMap<>();
		String where = where(criteria, params);

		long total = jdbc.sql("SELECT count(*) FROM vacancy v JOIN company c ON c.id = v.company_id WHERE " + where)
				.params(params).query(Long.class).single();

		List<VacancyCard> cards = List.of();
		if (total > 0) {
			Map<String, Object> pageParams = new LinkedHashMap<>(params);
			pageParams.put("limit", size);
			pageParams.put("offset", (long) (page - 1) * size);
			cards = jdbc.sql("SELECT " + CARD_COLUMNS + " FROM vacancy v JOIN company c ON c.id = v.company_id WHERE "
					+ where + " ORDER BY " + orderBy(criteria.sort()) + " LIMIT :limit OFFSET :offset")
					.params(pageParams).query(VacancyReadRepository::mapCard).list();
		}
		return new SearchResult(withSkills(cards), total);
	}

	/** Vacantes abiertas marcadas como destacadas, de la más reciente a la más antigua. */
	public List<VacancyCard> featuredVacancies(int limit) {
		List<VacancyCard> cards = jdbc.sql("SELECT " + CARD_COLUMNS + """
				 FROM vacancy v JOIN company c ON c.id = v.company_id
				WHERE v.status = 'OPEN' AND v.featured
				ORDER BY v.published_at DESC, v.id
				LIMIT :limit""").param("limit", limit).query(VacancyReadRepository::mapCard).list();
		return withSkills(cards);
	}

	/** Empresas destacadas con al menos una vacante abierta, la de publicación más reciente primero. */
	public List<FeaturedCompany> featuredCompanies(int limit) {
		return jdbc.sql("""
				SELECT c.id, c.name, c.city, c.short_description, count(*) AS open_vacancies
				FROM company c JOIN vacancy v ON v.company_id = c.id AND v.status = 'OPEN'
				WHERE c.featured
				GROUP BY c.id
				ORDER BY max(v.published_at) DESC, lower(unaccent(c.name)), c.id
				LIMIT :limit""").param("limit", limit)
				.query((rs, row) -> new FeaturedCompany(rs.getObject("id", UUID.class), rs.getString("name"),
						rs.getString("city"), rs.getString("short_description"), rs.getInt("open_vacancies")))
				.list();
	}

	/** Opciones de filtro con su conteo sobre todas las vacantes abiertas. */
	public FilterOptions filters() {
		long openCount = jdbc.sql("SELECT count(*) FROM vacancy WHERE status = 'OPEN'").query(Long.class).single();

		Map<Modality, Long> byModality = new LinkedHashMap<>();
		jdbc.sql("SELECT modality, count(*) AS total FROM vacancy WHERE status = 'OPEN' GROUP BY modality")
				.query((rs, row) -> byModality.put(Modality.valueOf(rs.getString("modality")), rs.getLong("total")))
				.list();
		List<FilterOptions.ModalityOption> modalities = new ArrayList<>();
		for (Modality modality : Modality.values()) {
			Long count = byModality.get(modality);
			if (count != null && count > 0) {
				modalities.add(new FilterOptions.ModalityOption(modality, count));
			}
		}

		List<FilterOptions.LocationOption> locations = jdbc.sql("""
				SELECT location, count(*) AS total FROM vacancy
				WHERE status = 'OPEN' AND location IS NOT NULL
				GROUP BY location ORDER BY total DESC, location""")
				.query((rs, row) -> new FilterOptions.LocationOption(rs.getString("location"), rs.getLong("total")))
				.list();

		List<FilterOptions.SkillOption> skills = jdbc.sql("""
				SELECT s.slug, s.name, count(DISTINCT v.id) AS total
				FROM vacancy v
				JOIN vacancy_requirement r ON r.vacancy_id = v.id
				JOIN skill s ON s.id = r.skill_id
				WHERE v.status = 'OPEN'
				GROUP BY s.id ORDER BY total DESC, s.name""")
				.query((rs, row) -> new FilterOptions.SkillOption(rs.getString("slug"), rs.getString("name"),
						rs.getLong("total")))
				.list();

		return new FilterOptions(openCount, modalities, locations, skills);
	}

	/** Una vacante abierta o cerrada con sus requisitos separados por tipo. */
	public Optional<VacancyDetail> detail(UUID id) {
		Optional<DetailRow> found = jdbc.sql("""
				SELECT v.id, v.title, v.summary, v.description, v.modality, v.location, v.hours_per_week,
				       v.published_at, v.status, v.featured,
				       c.id AS company_id, c.name AS company_name, c.city AS company_city,
				       c.short_description AS company_short_description
				FROM vacancy v JOIN company c ON c.id = v.company_id
				WHERE v.id = :id""").param("id", id).query((rs, n) -> new DetailRow(rs)).optional();
		if (found.isEmpty()) {
			return Optional.empty();
		}

		List<RequirementRow> rows = jdbc.sql("SELECT r.kind, r.min_level, s.slug, s.name FROM vacancy_requirement r "
				+ "JOIN skill s ON s.id = r.skill_id WHERE r.vacancy_id = :id "
				+ "ORDER BY " + REQUIRED_FIRST + ", r.min_level DESC, s.name")
				.param("id", id)
				.query((rs, n) -> new RequirementRow(RequirementKind.valueOf(rs.getString("kind")),
						new Requirement(new SkillRef(rs.getString("slug"), rs.getString("name")),
								rs.getInt("min_level"))))
				.list();
		List<Requirement> required = new ArrayList<>();
		List<Requirement> desirable = new ArrayList<>();
		List<SkillTag> skills = new ArrayList<>();
		for (RequirementRow row : rows) {
			SkillRef skill = row.requirement().skill();
			(row.kind() == RequirementKind.REQUIRED ? required : desirable).add(row.requirement());
			skills.add(new SkillTag(skill.slug(), skill.name(), row.kind()));
		}
		DetailRow d = found.get();
		return Optional.of(new VacancyDetail(d.id, d.title, d.summary, d.description, d.modality, d.location,
				d.hoursPerWeek, d.publishedAt, d.status, d.featured, d.company, skills,
				new Requirements(required, desirable)));
	}

	// -- Construcción del filtro ------------------------------------------------------------------------------

	private static String where(SearchCriteria criteria, Map<String, Object> params) {
		List<String> conditions = new ArrayList<>();
		conditions.add("v.status = 'OPEN'");

		int index = 0;
		for (String term : criteria.terms()) {
			String name = "term" + index++;
			params.put(name, SearchText.containsPattern(term));
			conditions.add(termCondition(":" + name));
		}

		if (!criteria.modalities().isEmpty()) {
			params.put("modalities", criteria.modalities().stream().map(Modality::name).toList());
			conditions.add("v.modality IN (:modalities)");
		}

		if (!criteria.locations().isEmpty()) {
			List<String> alternatives = new ArrayList<>();
			int location = 0;
			for (String value : criteria.locations()) {
				String name = "location" + location++;
				params.put(name, value);
				alternatives.add("lower(unaccent(v.location)) = lower(unaccent(:" + name + "))");
			}
			conditions.add("(" + String.join(" OR ", alternatives) + ")");
		}

		if (!criteria.skills().isEmpty()) {
			params.put("skills", criteria.skills());
			conditions.add("EXISTS (SELECT 1 FROM vacancy_requirement fr JOIN skill fs ON fs.id = fr.skill_id "
					+ "WHERE fr.vacancy_id = v.id AND fs.slug IN (:skills))");
		}
		return String.join(" AND ", conditions);
	}

	/** Un término debe aparecer en título, resumen, empresa o en el nombre o un sinónimo de una habilidad pedida. */
	private static String termCondition(String parameter) {
		String pattern = "unaccent(" + parameter + ")";
		return "(unaccent(v.title) ILIKE " + pattern
				+ " OR unaccent(v.summary) ILIKE " + pattern
				+ " OR unaccent(c.name) ILIKE " + pattern
				+ " OR EXISTS (SELECT 1 FROM vacancy_requirement tr JOIN skill ts ON ts.id = tr.skill_id"
				+ " WHERE tr.vacancy_id = v.id AND (unaccent(ts.name) ILIKE " + pattern
				+ " OR EXISTS (SELECT 1 FROM unnest(ts.aliases) AS alias(value) WHERE unaccent(alias.value) ILIKE "
				+ pattern + "))))";
	}

	/** Siempre termina en {@code v.id}: un orden total, para que las páginas no repitan ni omitan vacantes. */
	private static String orderBy(SearchSort sort) {
		return switch (sort) {
			case RECENT -> "v.published_at DESC, v.id";
			case COMPANY -> "lower(unaccent(c.name)), c.name, v.published_at DESC, v.id";
		};
	}

	private List<VacancyCard> withSkills(List<VacancyCard> cards) {
		if (cards.isEmpty()) {
			return cards;
		}
		List<UUID> ids = cards.stream().map(VacancyCard::id).toList();
		Map<UUID, List<SkillTag>> byVacancy = new LinkedHashMap<>();
		jdbc.sql("SELECT r.vacancy_id, r.kind, s.slug, s.name FROM vacancy_requirement r "
				+ "JOIN skill s ON s.id = r.skill_id WHERE r.vacancy_id IN (:ids) "
				+ "ORDER BY r.vacancy_id, " + REQUIRED_FIRST + ", r.min_level DESC, s.name")
				.param("ids", ids)
				.query((rs, n) -> byVacancy
						.computeIfAbsent(rs.getObject("vacancy_id", UUID.class), key -> new ArrayList<>())
						.add(new SkillTag(rs.getString("slug"), rs.getString("name"),
								RequirementKind.valueOf(rs.getString("kind")))))
				.list();
		return cards.stream().map(card -> card.withSkills(byVacancy.getOrDefault(card.id(), List.of()))).toList();
	}

	private static VacancyCard mapCard(ResultSet rs, int row) throws SQLException {
		return new VacancyCard(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("summary"),
				Modality.valueOf(rs.getString("modality")), rs.getString("location"), rs.getInt("hours_per_week"),
				rs.getObject("published_at", OffsetDateTime.class).toInstant(), rs.getBoolean("featured"),
				new CompanyRef(rs.getObject("company_id", UUID.class), rs.getString("company_name"),
						rs.getString("company_city")),
				List.of());
	}

	private record RequirementRow(RequirementKind kind, Requirement requirement) {
	}

	/** Columnas del detalle ya convertidas. */
	private static final class DetailRow {

		final UUID id;

		final String title;

		final String summary;

		final String description;

		final Modality modality;

		final String location;

		final int hoursPerWeek;

		final Instant publishedAt;

		final VacancyStatus status;

		final boolean featured;

		final CompanyDetail company;

		DetailRow(ResultSet rs) throws SQLException {
			id = rs.getObject("id", UUID.class);
			title = rs.getString("title");
			summary = rs.getString("summary");
			description = rs.getString("description");
			modality = Modality.valueOf(rs.getString("modality"));
			location = rs.getString("location");
			hoursPerWeek = rs.getInt("hours_per_week");
			publishedAt = rs.getObject("published_at", OffsetDateTime.class).toInstant();
			status = VacancyStatus.valueOf(rs.getString("status"));
			featured = rs.getBoolean("featured");
			company = new CompanyDetail(rs.getObject("company_id", UUID.class), rs.getString("company_name"),
					rs.getString("company_city"), rs.getString("company_short_description"));
		}

	}

}
