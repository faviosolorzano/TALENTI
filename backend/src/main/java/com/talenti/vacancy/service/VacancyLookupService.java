package com.talenti.vacancy.service;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.talenti.vacancy.VacancyInfo;
import com.talenti.vacancy.VacancyLookup;
import com.talenti.vacancy.persistence.VacancyReadRepository;

/** Implementación de {@link VacancyLookup}: una instantánea de lectura por llamada, como el resto de las lecturas. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class VacancyLookupService implements VacancyLookup {

	private final VacancyReadRepository repository;

	VacancyLookupService(VacancyReadRepository repository) {
		this.repository = repository;
	}

	@Override
	public List<VacancyInfo> listOpenForMatching() {
		return repository.openVacancyInfos();
	}

	@Override
	public Optional<VacancyInfo> findOpenForMatching(UUID id) {
		return repository.vacancyInfos(List.of(id), true).stream().findFirst();
	}

	@Override
	public List<VacancyInfo> findCards(Collection<UUID> ids) {
		return repository.vacancyInfos(List.copyOf(ids), false);
	}

}
