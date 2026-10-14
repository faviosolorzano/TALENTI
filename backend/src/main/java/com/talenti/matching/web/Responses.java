package com.talenti.matching.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.talenti.matching.domain.CompatibilityCalculator;
import com.talenti.matching.domain.CompatibilityResult;
import com.talenti.matching.domain.CompatibilityResult.Component;
import com.talenti.matching.domain.CompatibilityResult.ConditionItem;
import com.talenti.matching.domain.CompatibilityResult.Item;
import com.talenti.matching.domain.CompatibilityResult.ProfileItem;
import com.talenti.matching.domain.CompatibilityResult.RequirementItem;
import com.talenti.matching.domain.Explanation;
import com.talenti.matching.domain.MatchingParameters;
import com.talenti.matching.service.CompatibilityService.Evaluated;
import com.talenti.vacancy.VacancyInfo;

/** Cuerpos de las respuestas de {@code /compatibility} (spec 007, sección 6). */
final class Responses {

	private Responses() {
	}

	/** {@code vacancyId, status, percent, level, capped}; sin porcentaje ni veredicto si es insuficiente. */
	record Summary(UUID vacancyId, String status, Integer percent, String level, boolean capped) {

		static Summary of(UUID vacancyId, CompatibilityResult result) {
			return new Summary(vacancyId, result.status().name(), result.percent(),
					result.level() == null ? null : result.level().name(), result.capped());
		}

	}

	record SkillRef(String slug, String name) {
	}

	record ComponentBody(String key, int weight, BigDecimal score, boolean evaluable) {

		static ComponentBody of(Component component) {
			return new ComponentBody(component.key().name(), component.weight(),
					component.score() == null ? null : component.score().setScale(3, RoundingMode.HALF_UP),
					component.evaluable());
		}

	}

	record ExplanationBody(String source, String version, String text) {

		static ExplanationBody of(Explanation explanation) {
			return new ExplanationBody(explanation.source(), explanation.version(), explanation.text());
		}

	}

	record Weights(int required, int desirable, int conditions, int similarity) {
	}

	record Formula(String version, Weights weights, int cap, int minEvaluableWeight, boolean similarityEnabled) {

		static Formula of(MatchingParameters parameters) {
			return new Formula(CompatibilityCalculator.FORMULA_VERSION,
					new Weights(parameters.weightRequired(), parameters.weightDesirable(),
							parameters.weightConditions(), parameters.weightSimilarity()),
					parameters.cap(), parameters.minEvaluableWeight(), parameters.similarityEnabled());
		}

	}

	/** El resultado completo de una vacante: el resumen más las razones, listas, explicación y fórmula. */
	record Compatibility(UUID vacancyId, String status, Integer percent, String level, boolean capped,
			List<String> reasons, Integer informationBasis, List<ComponentBody> components,
			List<Map<String, Object>> matches, List<Map<String, Object>> gaps,
			List<Map<String, Object>> missingInfo, ExplanationBody explanation, Formula formula) {

		static Compatibility of(UUID vacancyId, CompatibilityResult result, Explanation explanation,
				MatchingParameters parameters) {
			return new Compatibility(vacancyId, result.status().name(), result.percent(),
					result.level() == null ? null : result.level().name(), result.capped(),
					result.reasons().stream().map(Enum::name).toList(), result.informationBasis(),
					result.components().stream().map(ComponentBody::of).toList(), items(result.matches()),
					items(result.gaps()), items(result.missingInfo()), ExplanationBody.of(explanation),
					Formula.of(parameters));
		}

	}

	private static List<Map<String, Object>> items(List<Item> items) {
		return items.stream().map(Responses::item).toList();
	}

	private static Map<String, Object> item(Item item) {
		Map<String, Object> body = new LinkedHashMap<>();
		switch (item) {
			case RequirementItem r -> {
				body.put("type", "REQUIREMENT");
				body.put("skill", new SkillRef(r.slug(), r.name()));
				body.put("requirementKind", r.kind().name());
				body.put("requiredLevel", r.requiredLevel());
				body.put("profileLevel", r.profileLevel());
				body.put("outcome", r.outcome().name());
				body.put("evidence", "DECLARED");
			}
			case ConditionItem c -> {
				body.put("type", "CONDITION");
				body.put("factor", c.factor().name());
				body.put("outcome", c.outcome().name());
				body.put("vacancyValue", c.vacancyValue());
				body.put("profileValue", c.profileValue());
			}
			case ProfileItem p -> {
				body.put("type", "PROFILE");
				body.put("key", p.key().name());
			}
		}
		return body;
	}

	// -- Tarjeta de vacante (la de la spec 004) ------------------------------------------------------------------

	record SkillTagBody(String slug, String name, String kind) {
	}

	record CompanyBody(UUID id, String name, String city) {
	}

	record VacancyCardBody(UUID id, String title, String summary, String modality, String location,
			int hoursPerWeek, Instant publishedAt, boolean featured, CompanyBody company, List<SkillTagBody> skills) {

		static VacancyCardBody of(VacancyInfo vacancy) {
			return new VacancyCardBody(vacancy.id(), vacancy.title(), vacancy.summary(), vacancy.modality().name(),
					vacancy.location(), vacancy.hoursPerWeek(), vacancy.publishedAt(), vacancy.featured(),
					new CompanyBody(vacancy.company().id(), vacancy.company().name(), vacancy.company().city()),
					vacancy.requirements().stream()
							.map(r -> new SkillTagBody(r.slug(), r.name(), r.kind().name())).toList());
		}

	}

	record ProfileState(boolean sufficient) {
	}

	record RecommendedItem(VacancyCardBody vacancy, Summary compatibility) {

		static RecommendedItem of(Evaluated evaluated) {
			return new RecommendedItem(VacancyCardBody.of(evaluated.vacancy()),
					Summary.of(evaluated.vacancy().id(), evaluated.result()));
		}

	}

	record RecommendedPage(ProfileState profile, List<RecommendedItem> items, int page, int size, long totalItems,
			int totalPages) {
	}

	record SummariesBody(boolean sufficient, List<Summary> items) {
	}

}
