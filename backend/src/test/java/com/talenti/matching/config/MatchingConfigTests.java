package com.talenti.matching.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.talenti.matching.domain.CompatibilityCalculator;
import com.talenti.matching.domain.ExplanationWriter;
import com.talenti.matching.domain.MatchingParameters;

/** 007-09: los parámetros viven en configuración y el backend no arranca si los pesos no suman 100. */
class MatchingConfigTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(MatchingConfig.class);

	@Test
	@DisplayName("007-09: sin configuración propia rigen los valores de RN-01: 50/15/20/15, tope 69, crédito 0,5, cosenos 0,20 y 0,60, mínimo 50, veredicto 80 y 60, similitud apagada")
	void t007_09_defaults() {
		runner.run(context -> {
			assertThat(context).hasNotFailed();
			MatchingParameters p = context.getBean(MatchingParameters.class);
			assertThat(p.weightRequired()).isEqualTo(50);
			assertThat(p.weightDesirable()).isEqualTo(15);
			assertThat(p.weightConditions()).isEqualTo(20);
			assertThat(p.weightSimilarity()).isEqualTo(15);
			assertThat(p.cap()).isEqualTo(69);
			assertThat(p.partialCredit()).isEqualByComparingTo("0.5");
			assertThat(p.cosineLow()).isEqualByComparingTo("0.20");
			assertThat(p.cosineHigh()).isEqualByComparingTo("0.60");
			assertThat(p.minEvaluableWeight()).isEqualTo(50);
			assertThat(p.verdictHigh()).isEqualTo(80);
			assertThat(p.verdictMedium()).isEqualTo(60);
			assertThat(p.similarityEnabled()).isFalse();
			assertThat(context).hasSingleBean(CompatibilityCalculator.class).hasSingleBean(ExplanationWriter.class);
			assertThat(context.getBean(ExplanationWriter.class).version()).isEqualTo("1");
		});
	}

	@Test
	@DisplayName("007-09: los valores de application.yml (los de producción) son los mismos de RN-01 y la similitud está apagada")
	void t007_09_ymlValues() {
		org.springframework.beans.factory.config.YamlPropertiesFactoryBean yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
		yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yml"));
		java.util.Properties file = yaml.getObject();
		assertThat(file.getProperty("talenti.matching.weights.required")).isEqualTo("50");
		runner.withPropertyValues(file.stringPropertyNames().stream().filter(name -> name.startsWith("talenti.matching."))
				.map(name -> name + "=" + file.getProperty(name)).toArray(String[]::new))
				.run(context -> {
					assertThat(context).hasNotFailed();
					MatchingParameters p = context.getBean(MatchingParameters.class);
					assertThat(p.weightRequired() + p.weightDesirable() + p.weightConditions() + p.weightSimilarity())
							.isEqualTo(100);
					assertThat(p.cap()).isEqualTo(69);
					assertThat(p.similarityEnabled()).isFalse();
					assertThat(p.cosineLow()).isEqualByComparingTo(new BigDecimal("0.20"));
				});
	}

	@Test
	@DisplayName("007-09: cambiar un peso por configuración cambia los parámetros del motor")
	void t007_09_overridesApply() {
		runner.withPropertyValues("talenti.matching.weights.required=40", "talenti.matching.weights.desirable=20",
				"talenti.matching.weights.similarity=20", "talenti.matching.cap=60", "talenti.matching.similarity.enabled=true")
				.run(context -> {
					assertThat(context).hasNotFailed();
					MatchingParameters p = context.getBean(MatchingParameters.class);
					assertThat(p.weightRequired()).isEqualTo(40);
					assertThat(p.cap()).isEqualTo(60);
					assertThat(p.similarityEnabled()).isTrue();
				});
	}

	@Test
	@DisplayName("007-09 (caso 20): si los cuatro pesos no suman 100 el contexto no arranca")
	void t007_09_weightsNotSummingTo100FailTheStartup() {
		runner.withPropertyValues("talenti.matching.weights.required=60").run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasStackTraceContaining("deben sumar 100");
		});
		runner.withPropertyValues("talenti.matching.weights.similarity=0").run(context -> assertThat(context).hasFailed());
	}

	@Test
	@DisplayName("007-09: umbrales incoherentes (coseno bajo >= alto, veredicto al revés, tope fuera de 0 a 100) tampoco arrancan")
	void t007_09_incoherentThresholdsFail() {
		runner.withPropertyValues("talenti.matching.cosine.low=0.7").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("talenti.matching.verdict.medium=90").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("talenti.matching.cap=101").run(context -> assertThat(context).hasFailed());
		runner.withPropertyValues("talenti.matching.partial-credit=1.5").run(context -> assertThat(context).hasFailed());
	}

}
