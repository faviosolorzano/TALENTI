package com.talenti;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.testcontainers.lifecycle.TestcontainersLifecycleApplicationContextInitializer;
import org.springframework.test.context.DynamicPropertyRegistrar;

import com.talenti.support.EmptyDatabaseConfiguration;

/** Un contexto descartado no puede apagar la base que comparten los demás contextos. */
class SharedDatabaseLifecycleTests {

	@ParameterizedTest
	@ValueSource(classes = { TestcontainersConfiguration.class, EmptyDatabaseConfiguration.class })
	void sharedDatabaseSurvivesClosingOneContext(Class<?> configuration) throws Exception {
		AtomicReference<String> url = new AtomicReference<>();
		new ApplicationContextRunner()
				.withInitializer(new TestcontainersLifecycleApplicationContextInitializer())
				.withUserConfiguration(configuration).run(context -> {
					assertThat(context).hasNotFailed();
					context.getBean(DynamicPropertyRegistrar.class).accept((name, value) -> {
						if ("spring.datasource.url".equals(name)) {
							url.set((String) value.get());
						}
					});
				});
		// Contexto ya cerrado, como al expulsarlo de la caché; el proceso de pruebas sigue vivo.
		try (var connection = DriverManager.getConnection(url.get(), TestcontainersConfiguration.APP_USER,
				TestcontainersConfiguration.APP_PASSWORD); var statement = connection.createStatement();
				var result = statement.executeQuery("SELECT 1")) {
			assertThat(result.next()).isTrue();
			assertThat(result.getInt(1)).isEqualTo(1);
		}
	}

}
