package com.talenti.vacancy.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
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
 * Lecturas públicas de vacantes (spec 004). Una sola transacción de solo lectura por llamada, para que el total y
 * la página salgan de la misma foto de la base. No escribe nada y no registra el texto de la búsqueda.
 */
@Service
@Transactional(readOnly = true)
public class VacancyQueryService {

	private final VacancyReadRepository repository;

	VacancyQueryService(VacancyReadRepository repository) {
		this.repository = repository;
	}

	/** Una {@code page} mayor que {@code totalPages} no es un error: devuelve {@code items: []} y los totales. */
	public VacancyPage search(SearchCriteria criteria, int page, int size) {
		SearchResult result = repository.search(criteria, page, size);
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
