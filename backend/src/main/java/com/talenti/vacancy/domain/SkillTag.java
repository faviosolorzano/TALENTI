package com.talenti.vacancy.domain;

/** Una habilidad de la tarjeta, con su tipo. Las obligatorias van primero. */
public record SkillTag(String slug, String name, RequirementKind kind) {
}
