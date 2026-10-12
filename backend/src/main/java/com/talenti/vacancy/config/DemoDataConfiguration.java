package com.talenti.vacancy.config;

import org.flywaydb.core.api.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Datos de ejemplo FICTICIOS (spec 004, duda 2). Con {@code talenti.demo-data.enabled=true} Flyway ve también
 * {@code classpath:db/demo} (migraciones {@code V1000} en adelante: 8 empresas y 12 vacantes de programación).
 * Con {@code false} esa carpeta ni se mira. La variable de entorno es {@code TALENTI_DEMO_DATA}; los valores por
 * defecto están en {@code application.yml}.
 */
@Configuration(proxyBeanMethods = false)
class DemoDataConfiguration {

	static final String DEMO_LOCATION = "classpath:db/demo";

	private static final Logger log = LoggerFactory.getLogger(DemoDataConfiguration.class);

	@Bean
	FlywayConfigurationCustomizer demoDataLocation(@Value("${talenti.demo-data.enabled:false}") boolean enabled) {
		return configuration -> {
			if (!enabled) {
				return;
			}
			Location[] current = configuration.getLocations();
			Location[] withDemo = new Location[current.length + 1];
			System.arraycopy(current, 0, withDemo, 0, current.length);
			withDemo[current.length] = new Location(DEMO_LOCATION);
			configuration.locations(withDemo);
			log.info("Datos de ejemplo activados: Flyway también carga {}", DEMO_LOCATION);
		};
	}

}
