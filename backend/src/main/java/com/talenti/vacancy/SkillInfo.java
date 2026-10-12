package com.talenti.vacancy;

import java.util.List;
import java.util.UUID;

/**
 * Una habilidad del catálogo (RN-03). Es parte de la interfaz pública del módulo vacancy: otros módulos la
 * reciben de {@link SkillCatalog}, nunca leen la tabla {@code skill}.
 *
 * @param slug    identificador estable en minúsculas ({@code [a-z0-9-]}), por ejemplo {@code spring-boot}
 * @param name    nombre para mostrar, por ejemplo {@code Spring Boot}
 * @param aliases sinónimos, por ejemplo {@code JS} para JavaScript
 */
public record SkillInfo(UUID id, String slug, String name, List<String> aliases) {

	public SkillInfo {
		aliases = List.copyOf(aliases);
	}

}
