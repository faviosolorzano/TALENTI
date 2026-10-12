package com.talenti.vacancy.web;

import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.talenti.vacancy.domain.FeaturedCompany;
import com.talenti.vacancy.service.VacancyQueryService;

/** Franja pública de empresas destacadas (RF-53). */
@RestController
@RequestMapping(VacancyWebConfig.API + "/companies")
class CompanyController {

	private static final int DEFAULT_FEATURED = 4;

	private static final int MAX_FEATURED = 8;

	private final VacancyQueryService vacancies;

	CompanyController(VacancyQueryService vacancies) {
		this.vacancies = vacancies;
	}

	@GetMapping("/featured")
	ResponseEntity<VacancyController.Items<FeaturedCompany>> featured(
			@RequestParam MultiValueMap<String, String> query) {
		int limit = SearchParameters.limit(query, DEFAULT_FEATURED, MAX_FEATURED);
		return PublicCache.ok(new VacancyController.Items<>(vacancies.featuredCompanies(limit)));
	}

}
