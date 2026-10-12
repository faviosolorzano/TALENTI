package com.talenti.vacancy.domain;

import java.util.List;

/** Una página del listado. {@code totalPages} es 0 si no hay resultados. */
public record VacancyPage(List<VacancyCard> items, int page, int size, long totalItems, int totalPages) {
}
