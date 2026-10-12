package com.talenti.vacancy.domain;

import java.util.UUID;

/** La empresa completa en el detalle de una vacante. */
public record CompanyDetail(UUID id, String name, String city, String shortDescription) {
}
