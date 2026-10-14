package com.talenti.vacancy.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.talenti.vacancy.VacancyInfo;
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
 * <p>La búsqueda (spec 004, duda 3 y enmienda 1) compara cada palabra útil, como expresión regular de palabra
 * completa o inicio de palabra, con {@code lower(unaccent(campo))}, y las de 4 letras o más también por parecido
 * ({@code similarity()} de pg_trgm sobre palabras enteras); una vacante coincide si aparece al menos una y el orden
 * por parecido suma puntos por campo. Sin {@code tsvector}. {@code unaccent} y {@code pg_trgm} las crea el
 * administrador de la base en {@code db/init/01-init-roles.sh}.
 *
 * <p><b>Límite de escala:</b> las expresiones regulares y {@code similarity()} se evalúan fila por fila sobre
 * {@code lower(unaccent(campo))}, sin índice. Con las decenas de vacantes del E2 tarda milisegundos; con decenas de
 * miles tarda segundos. Por eso la búsqueda apaga el JIT de PostgreSQL (compilar la consulta costaba más que
 * ejecutarla) y corre con un tiempo máximo ({@link #searchTimeout}): si se agota, {@link QueryTimeoutException}.
 * Mejora futura, sin hacer ahora: una columna normalizada con las palabras de cada vacante y un índice GIN
 * (pg_trgm) sobre ella, para que el filtro use índice.
 */
@Repository
public class VacancyReadRepository {

	/** Tiempo máximo de cada consulta de la búsqueda (propiedad {@code talenti.vacancy.search-timeout}). */
	static final String DEFAULT_SEARCH_TIMEOUT = "5s";

	/** SQLSTATE de PostgreSQL para una consulta cancelada (aquí, por {@code statement_timeout}). */
	private static final String QUERY_CANCELED = "57014";

	private static final String CARD_COLUMNS = """
			v.id, v.title, v.summary, v.modality, v.location, v.hours_per_week, v.published_at, v.featured,
			c.id AS company_id, c.name AS company_name, c.city AS company_city""";

	/** Obligatorias antes que deseables. */
	private static final String REQUIRED_FIRST = "CASE r.kind WHEN 'REQUIRED' THEN 0 ELSE 1 END";

	private final JdbcClient jdbc;

	private final Duration searchTimeout;

	VacancyReadRepository(JdbcClient jdbc,
			@Value("${talenti.vacancy.search-timeout:" + DEFAULT_SEARCH_TIMEOUT + "}") Duration searchTimeout) {
		this.jdbc = jdbc;
		this.searchTimeout = searchTimeout;
	}

	/**
	 * Vacantes abiertas que cumplen el filtro, en la página pedida ({@code page} desde 1).
	 *
	 * @throws QueryTimeoutException si una consulta supera el tiempo máximo; no lleva el texto buscado
	 */
	public SearchResult search(SearchCriteria criteria, int page, int size) {
		try {
			return doSearch(criteria, page, size);
		}
		catch (DataAccessException e) {
			if (isQueryCanceled(e)) {
				// Sin la causa: la consulta no debe volver a los registros con nada de lo que escribió el visitante.
				throw new QueryTimeoutException("La búsqueda superó el tiempo máximo");
			}
			throw e;
		}
	}

	private SearchResult doSearch(SearchCriteria criteria, int page, int size) {
		limitSearchCost();
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
					+ where + " ORDER BY " + orderBy(criteria) + " LIMIT :limit OFFSET :offset")
					.params(pageParams).query(VacancyReadRepository::mapCard).list();
		}
		return new SearchResult(withSkills(cards), total);
	}

	/**
	 * Apaga el JIT y fija el tiempo máximo de las consultas que siguen, solo dentro de la transacción actual
	 * ({@code set_config(..., true)} es {@code SET LOCAL}): al terminar, la conexión vuelve al grupo sin cambios y la
	 * instantánea {@code REPEATABLE_READ} del servicio no se altera. Los valores son del código, no del visitante.
	 * Sin transacción abierta no tendría efecto; {@code VacancyQueryService} siempre abre una.
	 */
	private void limitSearchCost() {
		jdbc.sql("SELECT set_config('jit', 'off', true), set_config('statement_timeout', :millis, true)")
				.param("millis", Long.toString(Math.max(1, searchTimeout.toMillis()))).query().singleRow();
	}

	private static boolean isQueryCanceled(Throwable error) {
		for (Throwable cause = error; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause instanceof SQLException sql && QUERY_CANCELED.equals(sql.getSQLState())) {
				return true;
			}
		}
		return false;
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

	// -- Consulta para otros módulos (VacancyLookup) --------------------------------------------------------------

	/** Todas las vacantes abiertas con sus requisitos (para calcular la compatibilidad). */
	public List<VacancyInfo> openVacancyInfos() {
		return loadInfos("WHERE v.status = 'OPEN'", null);
	}

	/** Las vacantes con esos ids; si {@code onlyOpen}, solo las abiertas. Los ids desconocidos se omiten. */
	public List<VacancyInfo> vacancyInfos(List<UUID> ids, boolean onlyOpen) {
		if (ids.isEmpty()) {
			return List.of();
		}
		return loadInfos("WHERE v.id IN (:ids)" + (onlyOpen ? " AND v.status = 'OPEN'" : ""), ids);
	}

	private List<VacancyInfo> loadInfos(String where, List<UUID> ids) {
		JdbcClient.StatementSpec query = jdbc.sql("SELECT " + CARD_COLUMNS
				+ ", v.status FROM vacancy v JOIN company c ON c.id = v.company_id " + where
				+ " ORDER BY v.published_at DESC, v.id");
		if (ids != null) {
			query = query.param("ids", ids);
		}
		List<InfoRow> rows = query.query((rs, n) -> new InfoRow(mapCard(rs, n),
				VacancyInfo.Status.valueOf(rs.getString("status")))).list();
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<VacancyInfo.Requirement>> requirements = new LinkedHashMap<>();
		jdbc.sql("SELECT r.vacancy_id, r.kind, r.min_level, s.slug, s.name FROM vacancy_requirement r "
				+ "JOIN skill s ON s.id = r.skill_id WHERE r.vacancy_id IN (:ids) "
				+ "ORDER BY r.vacancy_id, " + REQUIRED_FIRST + ", r.min_level DESC, s.name")
				.param("ids", rows.stream().map(row -> row.card().id()).toList())
				.query((rs, n) -> requirements
						.computeIfAbsent(rs.getObject("vacancy_id", UUID.class), key -> new ArrayList<>())
						.add(new VacancyInfo.Requirement(rs.getString("slug"), rs.getString("name"),
								VacancyInfo.RequirementKind.valueOf(rs.getString("kind")), rs.getInt("min_level"))))
				.list();
		return rows.stream().map(row -> {
			VacancyCard card = row.card();
			return new VacancyInfo(card.id(), card.title(), card.summary(),
					VacancyInfo.Modality.valueOf(card.modality().name()), card.location(), card.hoursPerWeek(),
					card.publishedAt(), card.featured(), row.status(),
					new VacancyInfo.Company(card.company().id(), card.company().name(), card.company().city()),
					requirements.getOrDefault(card.id(), List.of()));
		}).toList();
	}

	private record InfoRow(VacancyCard card, VacancyInfo.Status status) {
	}

	// -- Construcción del filtro ------------------------------------------------------------------------------

	private static String where(SearchCriteria criteria, Map<String, Object> params) {
		List<String> conditions = new ArrayList<>();
		conditions.add("v.status = 'OPEN'");

		// Una vacante coincide si al menos una palabra útil aparece, exacta o parecida, en algún campo (004-27, 004-31).
		List<String> termMatches = new ArrayList<>();
		for (int i = 0; i < criteria.terms().size(); i++) {
			String term = criteria.terms().get(i);
			params.put(termParameter(i), SearchText.matchPattern(term));
			String match = anyFieldMatches(":" + termParameter(i));
			Optional<String> fuzzy = SearchText.fuzzyWord(term);
			if (fuzzy.isPresent()) {
				params.put(fuzzyParameter(i), fuzzy.get());
				match = "(" + match + " OR " + anyFieldSimilar(":" + fuzzyParameter(i)) + ")";
			}
			termMatches.add(match);
		}
		if (!termMatches.isEmpty()) {
			conditions.add("(" + String.join(" OR ", termMatches) + ")");
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

	private static String termParameter(int index) {
		return "term" + index;
	}

	private static String fuzzyParameter(int index) {
		return "fuzzy" + index;
	}

	// -- Búsqueda por palabras (004-27, 004-28, 004-31, 004-32) ------------------------------------------------
	// Coincidencia exacta: la palabra llega como una expresión regular ya escapada (SearchText.matchPattern) y se
	// compara con lower(unaccent(campo)) usando el operador ~ de PostgreSQL.
	// Coincidencia por parecido: la palabra normalizada se compara con cada palabra entera del campo mediante
	// similarity() de pg_trgm (no word_similarity: "java" dentro de "javascript" daría 1,0).
	// Los fragmentos se reutilizan tanto para filtrar (anyFieldMatches, anyFieldSimilar) como para puntuar
	// (termScore, similarityScore), así que el filtro y el orden siempre coinciden.

	private static String fieldMatches(String column, String parameter) {
		return "lower(unaccent(" + column + ")) ~ " + parameter;
	}

	/**
	 * Alguna palabra entera de la columna se parece a la buscada. La constante del umbral es del código, no del
	 * visitante, por eso va en el texto de la consulta.
	 */
	private static String fieldSimilar(String column, String parameter) {
		return "EXISTS (SELECT 1 FROM regexp_split_to_table(lower(unaccent(" + column + ")), '[^[:alnum:]]+') "
				+ "AS fw(word) WHERE fw.word <> '' AND similarity(fw.word, " + parameter + ") >= "
				+ "CAST(" + SearchText.SIMILARITY_THRESHOLD + " AS real))";
	}

	private static String titleMatches(String parameter) {
		return fieldMatches("v.title", parameter);
	}

	private static String summaryMatches(String parameter) {
		return fieldMatches("v.summary", parameter);
	}

	private static String descriptionMatches(String parameter) {
		return fieldMatches("v.description", parameter);
	}

	private static String companyMatches(String parameter) {
		return fieldMatches("c.name", parameter);
	}

	/** Alguna habilidad pedida (obligatoria o deseable) cuyo nombre o sinónimo cumple la condición. */
	private static String skillMatches(BiFunction<String, String, String> field, String parameter) {
		return "EXISTS (SELECT 1 FROM vacancy_requirement tr JOIN skill ts ON ts.id = tr.skill_id"
				+ " WHERE tr.vacancy_id = v.id AND (" + field.apply("ts.name", parameter)
				+ " OR EXISTS (SELECT 1 FROM unnest(ts.aliases) AS alias(value) WHERE "
				+ field.apply("alias.value", parameter) + ")))";
	}

	private static String skillMatches(String parameter) {
		return skillMatches(VacancyReadRepository::fieldMatches, parameter);
	}

	private static String skillSimilar(String parameter) {
		return skillMatches(VacancyReadRepository::fieldSimilar, parameter);
	}

	/**
	 * La palabra aparece, tal cual, en título, resumen, descripción, empresa o en el nombre o un sinónimo de una
	 * habilidad pedida.
	 */
	private static String anyFieldMatches(String parameter) {
		return "(" + titleMatches(parameter) + " OR " + summaryMatches(parameter) + " OR "
				+ descriptionMatches(parameter) + " OR " + companyMatches(parameter) + " OR " + skillMatches(parameter)
				+ ")";
	}

	/** Una palabra parecida aparece en título, resumen, empresa o habilidades. La descripción no: es larga y ruidosa. */
	private static String anyFieldSimilar(String parameter) {
		return "(" + fieldSimilar("v.title", parameter) + " OR " + fieldSimilar("v.summary", parameter) + " OR "
				+ fieldSimilar("c.name", parameter) + " OR " + skillSimilar(parameter) + ")";
	}

	/**
	 * Puntos de una palabra por coincidencia exacta (004-28, 004-32): el mayor entre habilidad pedida o título (3),
	 * empresa (2) y resumen o descripción (1); 0 si no aparece. No se suman los campos de una misma palabra. Una
	 * habilidad obligatoria no pesa más que una deseable, y {@code featured} no interviene (principio 3).
	 */
	private static String termScore(String parameter) {
		return "(CASE WHEN " + skillMatches(parameter) + " OR " + titleMatches(parameter) + " THEN 3 WHEN "
				+ companyMatches(parameter) + " THEN 2 WHEN " + summaryMatches(parameter) + " OR "
				+ descriptionMatches(parameter) + " THEN 1 ELSE 0 END)";
	}

	/** Puntos de una palabra por parecido (004-31), con los mismos pesos por campo que {@link #termScore}. */
	private static String similarityScore(String parameter) {
		return "(CASE WHEN " + skillSimilar(parameter) + " OR " + fieldSimilar("v.title", parameter) + " THEN 3 WHEN "
				+ fieldSimilar("c.name", parameter) + " THEN 2 WHEN " + fieldSimilar("v.summary", parameter)
				+ " THEN 1 ELSE 0 END)";
	}

	/**
	 * Siempre termina en {@code v.id}: un orden total, para que las páginas no repitan ni omitan vacantes. Con
	 * {@code RELEVANCE} se ordena primero por los puntos de coincidencia exacta de todas las palabras y, solo para
	 * desempatar, por los puntos de parecido: un parecido nunca desplaza a una coincidencia exacta (004-31). Los
	 * parámetros {@code termN} y {@code fuzzyN} son los mismos del filtro.
	 */
	private static String orderBy(SearchCriteria criteria) {
		return switch (criteria.sort()) {
			case RECENT -> "v.published_at DESC, v.id";
			case COMPANY -> "lower(unaccent(c.name)), c.name, v.published_at DESC, v.id";
			case RELEVANCE -> {
				List<String> exact = new ArrayList<>();
				List<String> similar = new ArrayList<>();
				for (int i = 0; i < criteria.terms().size(); i++) {
					exact.add(termScore(":" + termParameter(i)));
					if (SearchText.fuzzyWord(criteria.terms().get(i)).isPresent()) {
						similar.add(similarityScore(":" + fuzzyParameter(i)));
					}
				}
				String order = "(" + String.join(" + ", exact) + ") DESC";
				if (!similar.isEmpty()) {
					order += ", (" + String.join(" + ", similar) + ") DESC";
				}
				yield order + ", v.published_at DESC, v.id";
			}
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
