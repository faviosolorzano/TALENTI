package com.talenti.profile.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros del módulo profile ({@code talenti.profile.*}), RN-14: viven en configuración, no en el código. Los
 * valores por defecto son los de la spec 006 (secciones 3 y 10).
 *
 * @param maxProjects máximo de proyectos por perfil (006-08, duda 10)
 * @param maxCycle    ciclo más alto que se acepta (006-05, duda 1); el mínimo es 1
 */
@ConfigurationProperties(prefix = "talenti.profile")
public record ProfileProperties(
		@DefaultValue("10") int maxProjects,
		@DefaultValue("10") int maxCycle,
		@DefaultValue Completeness completeness) {

	/** Regla de avance del perfil (RN-11, 006-09). Los pesos deben sumar 100. */
	public record Completeness(
			@DefaultValue Weights weights,
			@DefaultValue("3") int minSkills,
			@DefaultValue("1") int minModalities) {
	}

	public record Weights(
			@DefaultValue("15") int academic,
			@DefaultValue("25") int skills,
			@DefaultValue("15") int availability,
			@DefaultValue("25") int projects,
			@DefaultValue("10") int location,
			@DefaultValue("10") int about) {
	}

}
