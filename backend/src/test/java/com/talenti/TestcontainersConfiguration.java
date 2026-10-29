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
public class TestcontainersConfiguration {

	/** La misma imagen (y versión) que usa docker-compose.yml. */
	public static final DockerImageName PGVECTOR_IMAGE = DockerImageName.parse("pgvector/pgvector:0.8.2-pg18-trixie")
			.asCompatibleSubstituteFor("postgres");

	public static final String APP_USER = "talenti_app";

	/** Contraseña descartable, válida solo dentro del contenedor efímero de la prueba. */
	public static final String APP_PASSWORD = "app-solo-para-pruebas";

	/** Maven ejecuta las pruebas con backend/ como directorio de trabajo. */
	private static final Path INIT_SCRIPT = Path.of("..", "db", "init", "01-init-roles.sh").toAbsolutePath()
			.normalize();

	private static PostgreSQLContainer sharedContainer;

	/**
	 * Un solo contenedor para toda la ejecución de las pruebas: cada configuración distinta de Spring crea su
	 * propio contexto, y levantar un PostgreSQL por contexto gasta tiempo y memoria. Las pruebas usan datos
	 * propios (correos únicos), así que comparten la base sin estorbarse. Lo apaga Ryuk al terminar la JVM.
	 */
	private static synchronized PostgreSQLContainer shared() {
		if (sharedContainer == null) {
			sharedContainer = newContainer();
			sharedContainer.start();
		}
		return sharedContainer;
	}

	/**
	 * Un contenedor nuevo, igual al compartido (mismo script de inicialización y mismo rol de la aplicación). Lo
	 * usan las pruebas que necesitan una base vacía, por ejemplo la de la semilla apagada (004-03).
	 */
	public static PostgreSQLContainer newContainer() {
		return new PostgreSQLContainer(PGVECTOR_IMAGE)
				.withEnv("APP_DB_USER", APP_USER)
				.withEnv("APP_DB_PASSWORD", APP_PASSWORD)
				.withCopyFileToContainer(MountableFile.forHostPath(INIT_SCRIPT, 0755),
						"/docker-entrypoint-initdb.d/01-init-roles.sh");
	}

	/** Conecta Flyway y la aplicación con el rol de la aplicación, no con el superusuario del contenedor. */
	@Bean
	DynamicPropertyRegistrar applicationRoleProperties() {
		// No exponer el contenedor compartido como bean: Boot lo apagaría al expulsar cualquier contexto.
		PostgreSQLContainer postgres = shared();
		return registry -> {
			registry.add("spring.datasource.url", postgres::getJdbcUrl);
			registry.add("spring.datasource.username", () -> APP_USER);
			registry.add("spring.datasource.password", () -> APP_PASSWORD);
		};
	}

}
