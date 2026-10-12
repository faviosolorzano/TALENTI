package com.talenti.vacancy.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.talenti.vacancy.domain.FilterOptions;
import com.talenti.vacancy.domain.VacancyCard;
import com.talenti.vacancy.domain.VacancyDetail;
import com.talenti.vacancy.domain.VacancyPage;
import com.talenti.vacancy.service.VacancyQueryService;

/**
 * Lectura pública de vacantes (spec 004, sección 6). Sin sesión, sin cookie y sin datos de personas. Los
 * parámetros llegan como un mapa crudo para que una coma dentro de un valor no se parta en dos.
 */
@RestController
@RequestMapping(VacancyWebConfig.API + "/vacancies")
class VacancyController {

	private static final int DEFAULT_FEATURED = 4;

	private static final int MAX_FEATURED = 8;

	private final VacancyQueryService vacancies;

	VacancyController(VacancyQueryService vacancies) {
		this.vacancies = vacancies;
	}

	@GetMapping
	ResponseEntity<VacancyPage> list(@RequestParam MultiValueMap<String, String> query) {
		SearchParameters parameters = SearchParameters.parse(query);
		return PublicCache.ok(vacancies.search(parameters.criteria(), parameters.page(), parameters.size()));
	}

	@GetMapping("/featured")
	ResponseEntity<Items<VacancyCard>> featured(@RequestParam MultiValueMap<String, String> query) {
		int limit = SearchParameters.limit(query, DEFAULT_FEATURED, MAX_FEATURED);
		return PublicCache.ok(new Items<>(vacancies.featuredVacancies(limit)));
	}

	@GetMapping("/filters")
	ResponseEntity<FilterOptions> filters() {
		return PublicCache.ok(vacancies.filters());
	}

	@GetMapping("/{id}")
	ResponseEntity<VacancyDetail> detail(@PathVariable String id) {
		return PublicCache.ok(vacancies.detail(id));
	}

	/** Envoltorio {@code {items: [...]}} de las rutas de destacadas. */
	record Items<T>(List<T> items) {
	}

}
