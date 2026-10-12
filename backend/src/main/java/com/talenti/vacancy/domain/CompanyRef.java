package com.talenti.vacancy.domain;

import java.util.UUID;

/** La empresa dentro de una tarjeta de vacante. */
public record CompanyRef(UUID id, String name, String city) {
}
