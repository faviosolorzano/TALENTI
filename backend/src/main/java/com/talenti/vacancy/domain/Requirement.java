package com.talenti.vacancy.domain;

/** Un requisito: la habilidad y el nivel mínimo (RN-03: 1 básico, 2 intermedio, 3 avanzado). */
public record Requirement(SkillRef skill, int minLevel) {
}
