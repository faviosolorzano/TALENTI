package com.talenti.matching.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.talenti.matching.domain.CompatibilityCalculator;
import com.talenti.matching.domain.CompatibilityResult;
import com.talenti.matching.domain.Explanation;
import com.talenti.matching.domain.ExplanationWriter;
import com.talenti.matching.domain.Modality;
import com.talenti.matching.domain.ProfileFacts;
import com.talenti.matching.domain.RequirementKind;
import com.talenti.matching.domain.VacancyFacts;
import com.talenti.profile.ProfileReader;
import com.talenti.profile.ProfileSnapshot;
import com.talenti.profile.WorkModality;
import com.talenti.vacancy.VacancyInfo;
import com.talenti.vacancy.VacancyLookup;

/**
 * Compatibilidad entre el perfil de la sesión y las vacantes abiertas (spec 007). Lee el perfil por
 * {@link ProfileReader} y las vacantes por {@link VacancyLookup}; calcula con el motor puro y no guarda nada
 * (007, duda 2). El id del estudiante siempre viene de la sesión. No escribe en registros nada del perfil.
 */
@Service
public class CompatibilityService {

	private final ProfileReader profiles;

	private final VacancyLookup vacancies;

	private final CompatibilityCalculator calculator;

	private final ExplanationWriter explanations;

	CompatibilityService(ProfileReader profiles, VacancyLookup vacancies, CompatibilityCalculator calculator,
			ExplanationWriter explanations) {
		this.profiles = profiles;
		this.vacancies = vacancies;
		this.calculator = calculator;
		this.explanations = explanations;
	}

	/** Una vacante evaluada, con su explicación solo cuando se pide el detalle. */
	public record Evaluated(VacancyInfo vacancy, CompatibilityResult result) {
	}

	/**
	 * @param evidence de dónde sale el nivel de cada habilidad del perfil, por {@code slug} ({@code DECLARED},
	 *                 {@code OBSERVED}...). Solo se muestra: no entra en el cálculo (013-13, 013-14)
	 */
	public record Detail(Evaluated evaluated, Explanation explanation, Map<String, String> evidence) {

		public Detail {
			evidence = Map.copyOf(evidence);
		}

	}

	public record Recommended(boolean sufficient, List<Evaluated> items, int page, int size, long totalItems,
			int totalPages) {
	}

	public record Summaries(boolean sufficient, List<Evaluated> items) {
	}

	public CompatibilityCalculator calculator() {
		return calculator;
	}

	/** 404 si la vacante no existe, está cerrada o el id no es un UUID. */
	public Detail forVacancy(UUID userId, String rawVacancyId) {
		UUID id = parse(rawVacancyId);
		VacancyInfo vacancy = vacancies.findOpenForMatching(id).orElseThrow(VacancyNotFoundException::new);
		ProfileSnapshot snapshot = profiles.snapshot(userId);
		CompatibilityResult result = evaluate(facts(snapshot), vacancy);
		Map<String, String> evidence = new HashMap<>();
		for (ProfileSnapshot.Skill skill : snapshot.skills()) {
			evidence.put(skill.slug(), skill.evidence().name());
		}
		return new Detail(new Evaluated(vacancy, result), explanations.write(result), evidence);
	}

	/** Todas las vacantes abiertas, de mayor a menor compatibilidad (RN-04), las insuficientes al final. */
	public Recommended recommended(UUID userId, int page, int size) {
		ProfileFacts profile = facts(profiles.snapshot(userId));
		if (profile.skillLevels().isEmpty()) {
			return new Recommended(false, List.of(), page, size, 0, 0);
		}
		List<Evaluated> ordered = new ArrayList<>();
		for (VacancyInfo vacancy : vacancies.listOpenForMatching()) {
			ordered.add(new Evaluated(vacancy, evaluate(profile, vacancy)));
		}
		ordered.sort(Comparator.comparing((Evaluated e) -> !e.result().calculated())
				.thenComparing(Comparator.comparingInt((Evaluated e) -> e.result().percent() == null ? 0
						: e.result().percent()).reversed())
				.thenComparing(Comparator.comparing((Evaluated e) -> e.vacancy().publishedAt()).reversed())
				.thenComparing(e -> e.vacancy().id()));
		long total = ordered.size();
		int totalPages = (int) Math.min(Integer.MAX_VALUE, (total + size - 1) / size);
		long from = Math.min(total, (long) (page - 1) * size);
		int to = (int) Math.min(total, from + size);
		return new Recommended(true, List.copyOf(ordered.subList((int) from, to)), page, size, total, totalPages);
	}

	/** Un resumen por cada vacante abierta pedida; las cerradas o desconocidas se omiten, en el orden pedido. */
	public Summaries summaries(UUID userId, List<UUID> ids) {
		ProfileFacts profile = facts(profiles.snapshot(userId));
		if (profile.skillLevels().isEmpty()) {
			return new Summaries(false, List.of());
		}
		Set<UUID> distinct = new LinkedHashSet<>(ids);
		Map<UUID, VacancyInfo> byId = new HashMap<>();
		for (VacancyInfo vacancy : vacancies.findCards(distinct)) {
			if (vacancy.status() == VacancyInfo.Status.OPEN) {
				byId.put(vacancy.id(), vacancy);
			}
		}
		List<Evaluated> items = new ArrayList<>();
		for (UUID id : distinct) {
			VacancyInfo vacancy = byId.get(id);
			if (vacancy != null) {
				items.add(new Evaluated(vacancy, evaluate(profile, vacancy)));
			}
		}
		return new Summaries(true, items);
	}

	private CompatibilityResult evaluate(ProfileFacts profile, VacancyInfo vacancy) {
		// La similitud de proyectos sigue apagada (S12): no hay coseno que entregar.
		return calculator.calculate(profile, facts(vacancy), null);
	}

	private static ProfileFacts facts(ProfileSnapshot snapshot) {
		Map<String, Integer> levels = new HashMap<>();
		for (ProfileSnapshot.Skill skill : snapshot.skills()) {
			levels.merge(skill.slug(), skill.level(), Math::max);
		}
		Set<Modality> modalities = new LinkedHashSet<>();
		for (WorkModality modality : snapshot.acceptedModalities()) {
			modalities.add(Modality.valueOf(modality.name()));
		}
		return new ProfileFacts(levels, modalities, snapshot.location(), snapshot.hoursPerWeek());
	}

	private static VacancyFacts facts(VacancyInfo vacancy) {
		List<VacancyFacts.Requirement> requirements = vacancy.requirements().stream()
				.map(r -> new VacancyFacts.Requirement(r.slug(), r.name(), RequirementKind.valueOf(r.kind().name()),
						r.minLevel()))
				.toList();
		return new VacancyFacts(Modality.valueOf(vacancy.modality().name()), vacancy.location(),
				vacancy.hoursPerWeek(), requirements);
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
