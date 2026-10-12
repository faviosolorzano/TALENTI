package com.talenti.vacancy.config;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Datos de ejemplo FICTICIOS (spec 004, duda 2). Con {@code talenti.demo-data.enabled=true} se carga además
 * {@code classpath:db/demo} (8 empresas y 12 vacantes de programación) con un SEGUNDO Flyway que lleva su propio
 * historial ({@code flyway_schema_history_demo}). Con {@code false} este bean no existe: no se lee la carpeta ni se
 * crea esa tabla. La variable de entorno es {@code TALENTI_DEMO_DATA}; los valores por defecto están en
 * {@code application.yml}.
 *
 * <p>Por qué un Flyway aparte: así el Flyway del esquema queda estricto (sin {@code ignore-migration-patterns} ni
 * {@code out-of-order}) y una migración nueva del esquema (V4 en adelante) se aplica aunque la demo ya esté cargada,
 * porque el historial del esquema nunca llega a tener una versión 1000.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "talenti.demo-data.enabled", havingValue = "true")
class DemoDataConfiguration {

	private static final Logger log = LoggerFactory.getLogger(DemoDataConfiguration.class);

	/**
	 * Corre después de las migraciones del esquema ({@code flywayInitializer}): los datos necesitan las tablas y el
	 * catálogo de habilidades.
	 */
	@Bean(initMethod = "migrate")
	@DependsOn("flywayInitializer")
	DemoDataMigrator demoDataMigrator(DataSource dataSource, Environment environment) {
		if (environment.acceptsProfiles(Profiles.of("prod"))) {
			// Compose corre con el perfil prod y enciende la demo a propósito; un despliegue real no debe hacerlo.
			log.warn("ATENCIÓN: los datos de ejemplo FICTICIOS están ACTIVADOS con el perfil prod "
					+ "(talenti.demo-data.enabled=true / TALENTI_DEMO_DATA=true). Es correcto solo para la demo; "
					+ "en un entorno real apágalos.");
		}
		else {
			log.info("Datos de ejemplo activados: se cargan además de {}", DemoDataMigrator.LOCATION);
		}
		return new DemoDataMigrator(dataSource);
	}

}
