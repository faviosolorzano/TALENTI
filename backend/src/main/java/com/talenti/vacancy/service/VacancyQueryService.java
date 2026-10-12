package com.talenti.vacancy.service;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.talenti.vacancy.domain.FeaturedCompany;
import com.talenti.vacancy.domain.FilterOptions;
import com.talenti.vacancy.domain.SearchCriteria;
import com.talenti.vacancy.domain.SearchResult;
import com.talenti.vacancy.domain.VacancyCard;
import com.talenti.vacancy.domain.VacancyDetail;
import com.talenti.vacancy.domain.VacancyPage;
import com.talenti.vacancy.persistence.VacancyReadRepository;

/**
 * Lecturas públicas de vacantes (spec 004). No escribe nada y no registra el texto de la búsqueda. Una búsqueda que
 * supera su tiempo máximo responde 503 {@code search-timeout} ({@link SearchTimeoutException}).
 *
 * <p>Cada llamada es una transacción de solo lectura con aislamiento {@code REPEATABLE_READ}: en PostgreSQL eso es
 * una instantánea fija, así que el total, la página y las habilidades de las tarjetas salen de la misma foto de la
 * base aunque otra transacción cierre o publique una vacante a mitad de la llamada. (Con el READ COMMITTED por
 * defecto cada consulta ve su propia foto y {@code totalItems} podría no cuadrar con {@code items}.)
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class VacancyQueryService {

	private static final Logger log = LoggerFactory.getLogger(VacancyQueryService.class);

	private final VacancyReadRepository repository;

	VacancyQueryService(VacancyReadRepository repository) {
		this.repository = repository;
	}

	/** Una {@code page} mayor que {@code totalPages} no es un error: devuelve {@code items: []} y los totales. */
	public VacancyPage search(SearchCriteria criteria, int page, int size) {
		SearchResult result;
		try {
			result = repository.search(criteria, page, size);
		}
		catch (QueryTimeoutException e) {
			// Solo el hecho: ni el texto buscado ni la causa original llegan a los registros.
			log.warn("Búsqueda de vacantes cancelada por superar el tiempo máximo");
			throw new SearchTimeoutException();
		}
		int totalPages = (int) Math.min(Integer.MAX_VALUE, (result.total() + size - 1) / size);
		return new VacancyPage(result.items(), page, size, result.total(), totalPages);
	}

	public List<VacancyCard> featuredVacancies(int limit) {
		return repository.featuredVacancies(limit);
	}

	public List<FeaturedCompany> featuredCompanies(int limit) {
		return repository.featuredCompanies(limit);
	}

	public FilterOptions filters() {
		return repository.filters();
	}

	/** Abierta o cerrada. Un texto que no es un UUID da el mismo 404 que un id que no existe. */
	public VacancyDetail detail(String rawId) {
		UUID id = parse(rawId);
		return repository.detail(id).orElseThrow(VacancyNotFoundException::new);
	}

	private static UUID parse(String rawId) {
		try {
			return UUID.fromString(rawId);
		}
		catch (IllegalArgumentException e) {
			throw new VacancyNotFoundException();
		}
	}

}
