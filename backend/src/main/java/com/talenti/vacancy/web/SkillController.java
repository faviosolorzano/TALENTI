package com.talenti.vacancy.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.talenti.vacancy.SkillCatalog;

/**
 * Catálogo público de habilidades con sinónimos (spec 006-02). Sin sesión, sin cookie y sin datos de personas. Lo usa
 * el buscador de habilidades del perfil: el navegador encuentra por nombre o sinónimo sin distinguir mayúsculas ni
 * tildes. Es una lista simple, ordenada por nombre.
 */
@RestController
@RequestMapping(VacancyWebConfig.API + "/skills")
class SkillController {

	private final SkillCatalog catalog;

	SkillController(SkillCatalog catalog) {
		this.catalog = catalog;
	}

	@GetMapping
	ResponseEntity<List<SkillResponse>> list() {
		return PublicCache.ok(catalog.all().stream()
				.map(skill -> new SkillResponse(skill.slug(), skill.name(), skill.aliases())).toList());
	}

	/** Lo que ve el visitante: sin el identificador interno. */
	record SkillResponse(String slug, String name, List<String> aliases) {
	}

}
