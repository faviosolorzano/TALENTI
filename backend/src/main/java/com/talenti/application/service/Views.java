package com.talenti.application.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.talenti.application.domain.ApplicationStatus;

/**
 * Lo que la API devuelve (spec 008, sección 6). Ninguna respuesta lleva datos de sesiones de práctica ni del perfil
 * (008-12).
 */
public final class Views {

	private Views() {
	}

	public record Company(UUID id, String name, String city) {
	}

	/** Resumen de la vacante, incluso si ya está cerrada. */
	public record Vacancy(UUID id, String title, String status, Company company) {
	}

	public record ApplicationItem(UUID id, ApplicationStatus status, Instant submittedAt, Instant withdrawnAt,
			Vacancy vacancy) {
	}

	public record HistoryEntry(ApplicationStatus status, Instant at) {
	}

	/** El detalle: el resumen más el historial de estados, en orden. */
	public record Application(UUID id, ApplicationStatus status, Instant submittedAt, Instant withdrawnAt,
			Vacancy vacancy, List<HistoryEntry> history) {

		public Application {
			history = List.copyOf(history);
		}

	}

	public record Page(List<ApplicationItem> items, int page, int size, int totalItems, int totalPages) {

		public Page {
			items = List.copyOf(items);
		}

	}

	/** {@code active}: no retiradas ni vencidas. */
	public record Summary(int active, int withdrawn, ApplicationItem latest) {
	}

}
