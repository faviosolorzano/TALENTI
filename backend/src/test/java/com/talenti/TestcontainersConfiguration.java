package com.talenti;

import java.nio.file.Path;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * PostgreSQL 18 con pgvector, igual que en docker-compose.yml: el contenedor ejecuta el mismo
 * script de inicialización (db/init/01-init-roles.sh) y la aplicación se conecta con el rol de
 * la aplicación, que NO es superusuario. El superusuario del contenedor solo crea la base.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	/** La misma imagen (y versión) que usa docker-compose.yml. */
	static final DockerImageName PGVECTOR_IMAGE = DockerImageName.parse("pgvector/pgvector:0.8.2-pg18-trixie")
			.asCompatibleSubstituteFor("postgres");

	static final String APP_USER = "talenti_app";

	/** Contraseña descartable, válida solo dentro del contenedor efímero de la prueba. */
	static final String APP_PASSWORD = "app-solo-para-pruebas";

	/** Maven ejecuta las pruebas con backend/ como directorio de trabajo. */
	private static final Path INIT_SCRIPT = Path.of("..", "db", "init", "01-init-roles.sh").toAbsolutePath()
			.normalize();

	@Bean
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(PGVECTOR_IMAGE)
				.withEnv("APP_DB_USER", APP_USER)
				.withEnv("APP_DB_PASSWORD", APP_PASSWORD)
				.withCopyFileToContainer(MountableFile.forHostPath(INIT_SCRIPT, 0755),
						"/docker-entrypoint-initdb.d/01-init-roles.sh");
	}

	/** Conecta Flyway y la aplicación con el rol de la aplicación, no con el superusuario del contenedor. */
	@Bean
	DynamicPropertyRegistrar applicationRoleProperties(PostgreSQLContainer postgres) {
		return registry -> {
			registry.add("spring.datasource.url", postgres::getJdbcUrl);
			registry.add("spring.datasource.username", () -> APP_USER);
			registry.add("spring.datasource.password", () -> APP_PASSWORD);
		};
	}

}
