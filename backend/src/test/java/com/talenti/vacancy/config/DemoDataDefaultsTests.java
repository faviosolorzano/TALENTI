package com.talenti.vacancy.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * 004-03: valor por defecto de {@code talenti.demo-data.enabled} en cada perfil del {@code application.yml}
 * principal (sin el archivo de pruebas, que lo apaga): apagada por defecto y en {@code prod}; encendida en
 * {@code dev}; y la variable {@code TALENTI_DEMO_DATA} manda en todos.
 */
class DemoDataDefaultsTests {

	private static final String KEY = "talenti.demo-data.enabled";

	/** Lee los documentos del application.yml de producción y resuelve la propiedad para un perfil. */
	private static String resolve(String profile, String variable) throws IOException {
		List<PropertySource<?>> documents = new YamlPropertySourceLoader().load("application",
				new ClassPathResource("application.yml", DemoDataDefaultsTests.class.getClassLoader()));
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		if (variable != null) {
			environment.getPropertySources().addFirst(
					new MapPropertySource("env", java.util.Map.of("TALENTI_DEMO_DATA", variable)));
		}
		String value = null;
		for (PropertySource<?> document : documents) {
			Object onProfile = document.getProperty("spring.config.activate.on-profile");
			boolean applies = onProfile == null || (profile != null && profile.equals(onProfile));
			if (applies && document.containsProperty(KEY)) {
				// El documento más específico (declarado después) gana.
				environment.getPropertySources().addLast(document);
				value = environment.resolvePlaceholders(String.valueOf(document.getProperty(KEY)));
				environment.getPropertySources().remove(document.getName());
			}
		}
		return value;
	}

	@Test
	@DisplayName("004-03: sin variable, la semilla está apagada por defecto y en el perfil prod")
	void t004_03_offByDefaultAndInProd() throws IOException {
		assertThat(resolve(null, null)).isEqualTo("false");
		assertThat(resolve("prod", null)).isEqualTo("false");
	}

	@Test
	@DisplayName("004-03: en el perfil dev la semilla está encendida por defecto")
	void t004_03_onInDev() throws IOException {
		assertThat(resolve("dev", null)).isEqualTo("true");
	}

	@Test
	@DisplayName("004-03: TALENTI_DEMO_DATA manda en todos los perfiles (Compose la enciende sobre prod)")
	void t004_03_variableWins() throws IOException {
		assertThat(resolve("prod", "true")).isEqualTo("true");
		assertThat(resolve("dev", "false")).isEqualTo("false");
		assertThat(resolve(null, "true")).isEqualTo("true");
	}

}
