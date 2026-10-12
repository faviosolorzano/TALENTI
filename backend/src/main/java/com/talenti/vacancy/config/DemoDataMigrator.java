package com.talenti.vacancy.config;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

/**
 * Carga los datos de ejemplo con un Flyway propio: ubicación {@code classpath:db/demo} e historial
 * {@code flyway_schema_history_demo}, separado del historial del esquema.
 */
public class DemoDataMigrator {

	public static final String LOCATION = "classpath:db/demo";

	public static final String HISTORY_TABLE = "flyway_schema_history_demo";

	private final DataSource dataSource;

	public DemoDataMigrator(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/** Aplica las migraciones de ejemplo pendientes. Se puede repetir: si ya están cargadas, no hace nada. */
	public void migrate() {
		flyway(dataSource).migrate();
	}

	/** La configuración del Flyway de la demo (las pruebas la reutilizan para no inventar otra). */
	public static Flyway flyway(DataSource dataSource) {
		return Flyway.configure()
				.dataSource(dataSource)
				.locations(LOCATION)
				.table(HISTORY_TABLE)
				// El esquema ya tiene tablas (las del esquema real): sin esto Flyway se niega a usar un historial
				// nuevo. La línea base en 0 deja pendiente la V1000 de la demo.
				.baselineOnMigrate(true)
				.baselineVersion("0")
				.cleanDisabled(true)
				.load();
	}

}
