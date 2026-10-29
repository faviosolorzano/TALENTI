package com.talenti.matching.web;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.talenti.identity.CurrentUser;
import com.talenti.matching.service.CompatibilityService;
import com.talenti.matching.service.CompatibilityService.Detail;
import com.talenti.matching.service.CompatibilityService.Recommended;
import com.talenti.matching.service.CompatibilityService.Summaries;
import com.talenti.matching.service.DoraExplanationService;

/**
 * Compatibilidad del estudiante con las vacantes (spec 007, sección 6). El id de la persona sale siempre de la
 * sesión: ninguna ruta recibe un id de usuario (007-14). El rol y {@code Cache-Control: no-store} los impone
 * {@link StudentOnlyInterceptor} a toda la ruta. No guarda nada.
 */
@RestController
@RequestMapping(MatchingWebConfig.COMPATIBILITY)
class CompatibilityController {

	private final CompatibilityService compatibility;

	private final DoraExplanationService explanations;

	CompatibilityController(CompatibilityService compatibility, DoraExplanationService explanations) {
		this.compatibility = compatibility;
		this.explanations = explanations;
	}

	@GetMapping("/vacancies/{vacancyId}")
	Responses.Compatibility forVacancy(@AuthenticationPrincipal CurrentUser user, @PathVariable String vacancyId) {
		Detail detail = compatibility.forVacancy(user.id(), vacancyId);
		UUID id = detail.evaluated().vacancy().id();
		return Responses.Compatibility.of(id, detail.evaluated().result(), detail.explanation(),
				compatibility.calculator().parameters(), detail.evidence(), explanations.available());
	}

	/**
	 * 015-01: "DORA te explica", solo a pedido. Genera (o devuelve del caché) la explicación con los datos actuales; si
	 * la IA está apagada, falla o se alcanzó un límite, responde la plantilla (nunca 5xx por la IA).
	 */
	@PostMapping("/vacancies/{vacancyId}/explanation")
	Responses.DoraExplanationBody explain(@AuthenticationPrincipal CurrentUser user, @PathVariable String vacancyId) {
		return Responses.DoraExplanationBody.of(explanations.explain(user.id(), vacancyId));
	}

	@GetMapping("/recommended")
	Responses.RecommendedPage recommended(@AuthenticationPrincipal CurrentUser user,
			@RequestParam MultiValueMap<String, String> query) {
		Parameters.Paging paging = Parameters.paging(query);
		Recommended result = compatibility.recommended(user.id(), paging.page(), paging.size());
		return new Responses.RecommendedPage(new Responses.ProfileState(result.sufficient()),
				result.items().stream().map(Responses.RecommendedItem::of).toList(), result.page(), result.size(),
				result.totalItems(), result.totalPages());
	}

	@GetMapping("/summaries")
	Responses.SummariesBody summaries(@AuthenticationPrincipal CurrentUser user,
			@RequestParam MultiValueMap<String, String> query) {
		Summaries result = compatibility.summaries(user.id(), Parameters.vacancyIds(query));
		return new Responses.SummariesBody(result.sufficient(), result.items().stream()
				.map(e -> Responses.Summary.of(e.vacancy().id(), e.result())).toList());
	}

}
