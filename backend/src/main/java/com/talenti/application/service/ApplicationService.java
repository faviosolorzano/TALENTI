package com.talenti.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.talenti.application.persistence.ApplicationRepository;
import com.talenti.application.persistence.ApplicationRow;
import com.talenti.shared.audit.AuditEvent;
import com.talenti.shared.audit.AuditRecorder;
import com.talenti.shared.web.ApiException;
import com.talenti.vacancy.VacancyInfo;
import com.talenti.vacancy.VacancyLookup;

/**
 * Casos de uso de la postulación (spec 008). Toda operación recibe el id de la persona que salió de la sesión; ninguna
 * acepta el de otra, y una postulación ajena es igual a una inexistente (404). Enviar y retirar guardan la fila, el
 * evento de historial y la auditoría en UNA transacción. No guarda copia del perfil, no toca sesiones de práctica y no
 * registra datos personales (solo ids).
 */
@Service
public class ApplicationService {

	public static final int DEFAULT_PAGE_SIZE = 10;

	public static final int MAX_PAGE_SIZE = 24;

	private final ApplicationRepository repository;

	private final VacancyLookup vacancies;

	private final AuditRecorder audit;

	private final Clock clock;

	ApplicationService(ApplicationRepository repository, VacancyLookup vacancies, AuditRecorder audit, Clock clock) {
		this.repository = repository;
		this.vacancies = vacancies;
		this.audit = audit;
		this.clock = clock;
	}

	/** 201: postula a una vacante abierta. */
	@Transactional
	public Views.Application submit(UUID userId, UUID vacancyId) {
		VacancyInfo vacancy = vacancies.findCards(List.of(vacancyId)).stream().findFirst()
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "vacancy-not-found"));
		if (vacancy.status() != VacancyInfo.Status.OPEN) {
			throw new ApiException(HttpStatus.CONFLICT, "vacancy-closed");
		}
		UUID id = UUID.randomUUID();
		Instant now = clock.instant();
		try {
			repository.insert(id, userId, vacancyId, now);
		}
		catch (DuplicateKeyException duplicate) {
			throw new ApiException(HttpStatus.CONFLICT, "application-already-exists");
		}
		audit.record(new AuditEvent("APPLICATION_SUBMITTED", userId, "application", id,
				Map.of("vacancyId", vacancyId.toString())));
		return detail(userId, id);
	}

	/** Retira una postulación propia enviada; desde cualquier otro estado, 409. */
	@Transactional
	public Views.Application withdraw(UUID userId, UUID id) {
		ApplicationRow row = repository.findOwned(id, userId).orElseThrow(ApplicationService::notFound);
		if (!row.status().canWithdraw() || !repository.withdraw(id, userId, clock.instant())) {
			throw new ApiException(HttpStatus.CONFLICT, "application-not-withdrawable");
		}
		audit.record(new AuditEvent("APPLICATION_WITHDRAWN", userId, "application", id,
				Map.of("vacancyId", row.vacancyId().toString())));
		return detail(userId, id);
	}

	@Transactional(readOnly = true)
	public Views.Application detail(UUID userId, UUID id) {
		ApplicationRow row = repository.findOwned(id, userId).orElseThrow(ApplicationService::notFound);
		Views.ApplicationItem item = items(List.of(row)).get(0);
		List<Views.HistoryEntry> history = repository.history(id).stream()
				.map(stage -> new Views.HistoryEntry(stage.status(), stage.at())).toList();
		return new Views.Application(item.id(), item.status(), item.submittedAt(), item.withdrawnAt(), item.vacancy(),
				history);
	}

	@Transactional(readOnly = true)
	public Views.Page list(UUID userId, UUID vacancyId, int page, int size) {
		int total = repository.count(userId, vacancyId);
		List<ApplicationRow> rows = repository.list(userId, vacancyId, size, (page - 1) * size);
		return new Views.Page(items(rows), page, size, total, (total + size - 1) / size);
	}

	@Transactional(readOnly = true)
	public Views.Summary summary(UUID userId) {
		Views.ApplicationItem latest = repository.latest(userId).map(row -> items(List.of(row)).get(0)).orElse(null);
		return new Views.Summary(repository.countActive(userId), repository.countWithdrawn(userId), latest);
	}

	// ------------------------------------------------------------------------------------------ piezas

	/** Une cada postulación con el resumen de su vacante (abierta o cerrada) por la interfaz pública de vacancy. */
	private List<Views.ApplicationItem> items(List<ApplicationRow> rows) {
		Map<UUID, Views.Vacancy> byId = new HashMap<>();
		for (VacancyInfo vacancy : vacancies.findCards(rows.stream().map(ApplicationRow::vacancyId).distinct().toList())) {
			byId.put(vacancy.id(), new Views.Vacancy(vacancy.id(), vacancy.title(), vacancy.status().name(),
					new Views.Company(vacancy.company().id(), vacancy.company().name(), vacancy.company().city())));
		}
		List<Views.ApplicationItem> items = new ArrayList<>();
		for (ApplicationRow row : rows) {
			items.add(new Views.ApplicationItem(row.id(), row.status(), row.submittedAt(), row.withdrawnAt(),
					byId.get(row.vacancyId())));
		}
		return items;
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "application-not-found");
	}

}
