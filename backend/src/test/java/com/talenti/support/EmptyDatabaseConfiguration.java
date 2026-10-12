package com.talenti.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.talenti.TestcontainersConfiguration;

/**
 * Una base de datos APARTE de la compartida por el resto de las pruebas, para las que necesitan saber que nunca
 * se cargaron datos de ejemplo (004-03 con la semilla apagada). Es el mismo PostgreSQL con el mismo script de
 * inicialización y el mismo rol de la aplicación sin superusuario. Un solo contenedor para todos los contextos que
 * la importen; lo apaga Ryuk al terminar la JVM.
 */
@TestConfiguration(proxyBeanMethods = false)
public class EmptyDatabaseConfiguration {

	private static PostgreSQLContainer container;

	@Bean(destroyMethod = "")
	PostgreSQLContainer emptyPostgresContainer() {
		return shared();
	}

	private static synchronized PostgreSQLContainer shared() {
		if (container == null) {
			container = TestcontainersConfiguration.newContainer();
		}
		return container;
	}

	@Bean
	DynamicPropertyRegistrar emptyDatabaseProperties(PostgreSQLContainer emptyPostgresContainer) {
		return registry -> {
			registry.add("spring.datasource.url", emptyPostgresContainer::getJdbcUrl);
			registry.add("spring.datasource.username", () -> TestcontainersConfiguration.APP_USER);
			registry.add("spring.datasource.password", () -> TestcontainersConfiguration.APP_PASSWORD);
		};
	}

}
