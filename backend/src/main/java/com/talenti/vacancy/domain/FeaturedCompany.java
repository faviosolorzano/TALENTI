package com.talenti.vacancy.domain;

import java.util.UUID;

/** Empresa destacada con al menos una vacante abierta (franja de la portada, RF-53). */
public record FeaturedCompany(UUID id, String name, String city, String shortDescription, int openVacancies) {
}
