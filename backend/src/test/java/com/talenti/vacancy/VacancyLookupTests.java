package com.talenti.vacancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** 007-17 (spec 007, sección 5): {@link VacancyLookup} entrega vacantes con requisitos sin exponer las tablas. */
class VacancyLookupTests extends VacancyIntegrationTest {

	@Autowired
	private VacancyLookup lookup;

	@Test
	@DisplayName("007-17: listOpenForMatching trae las vacantes abiertas con empresa, datos de tarjeta y requisitos (obligatorios primero)")
	void t007_17_listOpen() {
		List<VacancyInfo> open = lookup.listOpenForMatching();

		assertThat(open).hasSizeGreaterThanOrEqualTo(12).allSatisfy(v -> assertThat(v.status()).isEqualTo(VacancyInfo.Status.OPEN));
		VacancyInfo backend = open.stream().filter(v -> v.id().toString().equals(BACKEND_JAVA)).findFirst().orElseThrow();
		assertThat(backend.title()).isEqualTo("Practicante Backend Java");
		assertThat(backend.modality()).isEqualTo(VacancyInfo.Modality.REMOTE);
		assertThat(backend.location()).isNull();
		assertThat(backend.hoursPerWeek()).isEqualTo(20);
		assertThat(backend.publishedAt()).isNotNull();
		assertThat(backend.company().name()).isNotBlank();
		assertThat(backend.requirements()).extracting(VacancyInfo.Requirement::slug)
				.containsExactly("java", "sql", "spring-boot", "docker", "git");
		assertThat(backend.requirements().get(0)).isEqualTo(
				new VacancyInfo.Requirement("java", "Java", VacancyInfo.RequirementKind.REQUIRED, 2));
		assertThat(backend.requirements().get(4).kind()).isEqualTo(VacancyInfo.RequirementKind.DESIRABLE);
	}

	@Test
	@DisplayName("007-17: findOpenForMatching solo devuelve vacantes abiertas; una cerrada o desconocida da vacío")
	void t007_17_findOpen() {
		UUID closed = insertVacancy("Cerrada lookup 007", "Resumen.", OffsetDateTime.now(ZoneOffset.UTC), "CLOSED", false);
		try {
			assertThat(lookup.findOpenForMatching(UUID.fromString(BACKEND_JAVA))).isPresent();
			assertThat(lookup.findOpenForMatching(closed)).isEmpty();
			assertThat(lookup.findOpenForMatching(UUID.randomUUID())).isEmpty();
		}
		finally {
			deleteVacancy(closed);
		}
	}

	@Test
	@DisplayName("007-17: findCards incluye las cerradas con su estado y omite los ids desconocidos")
	void t007_17_findCards() {
		UUID closed = insertVacancy("Cerrada tarjetas 007", "Resumen.", OffsetDateTime.now(ZoneOffset.UTC), "CLOSED", false);
		try {
			List<VacancyInfo> cards = lookup.findCards(List.of(closed, UUID.fromString(BACKEND_JAVA), UUID.randomUUID()));

			assertThat(cards).hasSize(2);
			assertThat(cards).filteredOn(v -> v.id().equals(closed)).singleElement()
					.satisfies(v -> assertThat(v.status()).isEqualTo(VacancyInfo.Status.CLOSED));
			assertThat(cards).filteredOn(v -> v.id().toString().equals(BACKEND_JAVA)).singleElement()
					.satisfies(v -> assertThat(v.status()).isEqualTo(VacancyInfo.Status.OPEN));
			assertThat(lookup.findCards(List.of())).isEmpty();
		}
		finally {
			deleteVacancy(closed);
		}
	}

}
