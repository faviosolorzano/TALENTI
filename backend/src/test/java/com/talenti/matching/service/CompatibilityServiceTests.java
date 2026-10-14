package com.talenti.matching.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.matching.domain.CompatibilityCalculator;
import com.talenti.matching.domain.ExplanationWriter;
import com.talenti.matching.domain.MatchingParameters;
import com.talenti.matching.service.CompatibilityService.Evaluated;
import com.talenti.profile.ProfileCompleteness;
import com.talenti.profile.ProfileReader;
import com.talenti.profile.ProfileSnapshot;
import com.talenti.profile.WorkModality;
import com.talenti.vacancy.VacancyInfo;
import com.talenti.vacancy.VacancyLookup;

/** Orden de las recomendadas, resúmenes y 404 sin base de datos (007-14 a 007-16 y caso 24 de la tabla). */
class CompatibilityServiceTests {

	private static final UUID USER = UUID.randomUUID();

	private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

	private final Map<UUID, VacancyInfo> vacancies = new HashMap<>();

	private ProfileSnapshot snapshot = new ProfileSnapshot(List.of(new ProfileSnapshot.Skill("java", 2)), 20,
			Set.of(WorkModality.REMOTE), "Lima", List.of());

	private final ProfileReader profiles = new ProfileReader() {

		@Override
		public ProfileSnapshot snapshot(UUID userId) {
			assertThat(userId).isEqualTo(USER);
			return snapshot;
		}

		@Override
		public ProfileCompleteness completeness(UUID userId) {
			throw new UnsupportedOperationException();
		}

	};

	private final VacancyLookup lookup = new VacancyLookup() {

		@Override
		public List<VacancyInfo> listOpenForMatching() {
			return vacancies.values().stream().filter(v -> v.status() == VacancyInfo.Status.OPEN).toList();
		}

		@Override
		public Optional<VacancyInfo> findOpenForMatching(UUID id) {
			return Optional.ofNullable(vacancies.get(id)).filter(v -> v.status() == VacancyInfo.Status.OPEN);
		}

		@Override
		public List<VacancyInfo> findCards(Collection<UUID> ids) {
			return ids.stream().map(vacancies::get).filter(java.util.Objects::nonNull).toList();
		}

	};

	private final CompatibilityService service;

	CompatibilityServiceTests() {
		MatchingParameters parameters = new MatchingParameters(50, 15, 20, 15, 69, new BigDecimal("0.5"),
				new BigDecimal("0.20"), new BigDecimal("0.60"), 50, 80, 60, false);
		Map<String, String> templates = new HashMap<>();
		java.util.Properties properties = new java.util.Properties();
		try (java.io.InputStream in = getClass().getClassLoader().getResourceAsStream("matching/explanation.es.properties");
				java.io.InputStreamReader reader = new java.io.InputStreamReader(in,
						java.nio.charset.StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		catch (java.io.IOException e) {
			throw new IllegalStateException(e);
		}
		properties.forEach((key, value) -> templates.put((String) key, (String) value));
		service = new CompatibilityService(profiles, lookup, new CompatibilityCalculator(parameters),
				new ExplanationWriter(templates, 69));
	}

	/** Una vacante remota de 20 horas que pide Java 2 (obligatorio) y, si {@code extraRequired}, SQL (ausente). */
	private VacancyInfo add(String id, Instant publishedAt, boolean featured, VacancyInfo.Status status,
			boolean extraRequired, boolean noRequirements) {
		List<VacancyInfo.Requirement> requirements = noRequirements ? List.of()
				: extraRequired
						? List.of(new VacancyInfo.Requirement("java", "Java", VacancyInfo.RequirementKind.REQUIRED, 2),
								new VacancyInfo.Requirement("sql", "SQL", VacancyInfo.RequirementKind.REQUIRED, 1))
						: List.of(new VacancyInfo.Requirement("java", "Java", VacancyInfo.RequirementKind.REQUIRED, 2));
		VacancyInfo vacancy = new VacancyInfo(UUID.fromString(id), "Vacante " + id.charAt(0), "Resumen",
				VacancyInfo.Modality.REMOTE, null, 20, publishedAt, featured, status,
				new VacancyInfo.Company(UUID.randomUUID(), "Empresa Demo", "Lima"), requirements);
		vacancies.put(vacancy.id(), vacancy);
		return vacancy;
	}

	private static String id(char c) {
		return c + "0000000-0000-4000-8000-000000000000";
	}

	@Test
	@DisplayName("007-15 (caso 24): descendente por porcentaje; a igualdad, la más reciente; el empate total por id; las INSUFFICIENT al final")
	void t007_15_orderingAndTies() {
		add(id('a'), NOW.minusSeconds(100), false, VacancyInfo.Status.OPEN, false, false);
		add(id('b'), NOW.minusSeconds(10), false, VacancyInfo.Status.OPEN, false, false);
		add(id('c'), NOW.minusSeconds(10), false, VacancyInfo.Status.OPEN, false, false);
		add(id('d'), NOW, true, VacancyInfo.Status.OPEN, true, false);
		add(id('e'), NOW, false, VacancyInfo.Status.OPEN, false, true);

		CompatibilityService.Recommended page = service.recommended(USER, 1, 24);

		assertThat(page.sufficient()).isTrue();
		assertThat(page.items()).extracting(e -> e.vacancy().id().toString().charAt(0))
				.containsExactly('b', 'c', 'a', 'd', 'e');
		// 'd' es destacada y la más reciente, pero su porcentaje es menor: la destacada no mueve el orden (RN-04).
		assertThat(page.items().get(3).result().percent()).isLessThan(page.items().get(0).result().percent());
		assertThat(page.items().get(4).result().calculated()).isFalse();
	}

	@Test
	@DisplayName("007-15: pagina sobre la lista ordenada; una página fuera de rango devuelve items vacíos y los totales")
	void t007_15_pagination() {
		for (char c = 'a'; c <= 'e'; c++) {
			add(id(c), NOW.minusSeconds(c), false, VacancyInfo.Status.OPEN, false, false);
		}

		CompatibilityService.Recommended second = service.recommended(USER, 2, 2);
		CompatibilityService.Recommended beyond = service.recommended(USER, 9, 2);

		assertThat(second.items()).hasSize(2);
		assertThat(second.totalItems()).isEqualTo(5);
		assertThat(second.totalPages()).isEqualTo(3);
		assertThat(second.page()).isEqualTo(2);
		assertThat(beyond.items()).isEmpty();
		assertThat(beyond.totalItems()).isEqualTo(5);
	}

	@Test
	@DisplayName("007-15: las vacantes cerradas no se calculan ni se recomiendan")
	void t007_15_closedAreSkipped() {
		add(id('a'), NOW, false, VacancyInfo.Status.OPEN, false, false);
		add(id('b'), NOW, false, VacancyInfo.Status.CLOSED, false, false);

		assertThat(service.recommended(USER, 1, 9).items()).hasSize(1);
	}

	@Test
	@DisplayName("007-15: un perfil sin habilidades da sufficient falso, items vacíos y totales en cero")
	void t007_15_profileWithoutSkills() {
		add(id('a'), NOW, false, VacancyInfo.Status.OPEN, false, false);
		snapshot = new ProfileSnapshot(List.of(), null, Set.of(), null, List.of());

		CompatibilityService.Recommended page = service.recommended(USER, 1, 9);
		CompatibilityService.Summaries summaries = service.summaries(USER, List.of(UUID.fromString(id('a'))));

		assertThat(page.sufficient()).isFalse();
		assertThat(page.items()).isEmpty();
		assertThat(page.totalItems()).isZero();
		assertThat(page.totalPages()).isZero();
		assertThat(summaries.sufficient()).isFalse();
		assertThat(summaries.items()).isEmpty();
	}

	@Test
	@DisplayName("007-16: summaries devuelve un resumen por vacante abierta pedida y omite las cerradas y las desconocidas")
	void t007_16_summariesOmitClosedAndUnknown() {
		add(id('a'), NOW, false, VacancyInfo.Status.OPEN, false, false);
		add(id('b'), NOW, false, VacancyInfo.Status.CLOSED, false, false);
		add(id('c'), NOW, false, VacancyInfo.Status.OPEN, true, false);

		CompatibilityService.Summaries result = service.summaries(USER, List.of(UUID.fromString(id('c')),
				UUID.fromString(id('b')), UUID.randomUUID(), UUID.fromString(id('a')), UUID.fromString(id('c'))));

		assertThat(result.sufficient()).isTrue();
		assertThat(result.items()).extracting(Evaluated::vacancy).extracting(VacancyInfo::id)
				.containsExactly(UUID.fromString(id('c')), UUID.fromString(id('a')));
	}

	@Test
	@DisplayName("007-14: un id mal formado, una vacante inexistente y una cerrada dan el mismo 404 vacancy-not-found")
	void t007_14_notFoundIsIndistinguishable() {
		add(id('b'), NOW, false, VacancyInfo.Status.CLOSED, false, false);

		for (String raw : List.of("no-es-un-uuid", UUID.randomUUID().toString(), id('b'), "")) {
			assertThatThrownBy(() -> service.forVacancy(USER, raw)).isInstanceOf(VacancyNotFoundException.class)
					.extracting("code").isEqualTo("vacancy-not-found");
		}
	}

	@Test
	@DisplayName("007-14: una vacante abierta devuelve el resultado completo con su explicación por plantilla")
	void t007_14_detail() {
		VacancyInfo vacancy = add(id('a'), NOW, false, VacancyInfo.Status.OPEN, false, false);

		CompatibilityService.Detail detail = service.forVacancy(USER, vacancy.id().toString());

		assertThat(detail.evaluated().result().percent()).isEqualTo(100);
		assertThat(detail.explanation().source()).isEqualTo("TEMPLATE");
	}

}
