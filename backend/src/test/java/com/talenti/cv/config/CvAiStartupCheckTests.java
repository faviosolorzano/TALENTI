package com.talenti.cv.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import com.talenti.cv.AdapterInfo;
import com.talenti.cv.EnhancedCvExtractionPort;
import com.talenti.cv.RawSuggestion;
import com.talenti.vacancy.SkillInfo;

/** 009-11: la IA real viene apagada y no se puede encender sin su implementación (el arranque falla con un mensaje claro). */
class CvAiStartupCheckTests {

	private static AiCvProperties properties(boolean enabled) {
		return new AiCvProperties("simulated", Duration.ofDays(30),
				new AiCvProperties.Cv(enabled, "", 12_000, 3_000, Duration.ofSeconds(30)),
				new AiCvProperties.OpenAi("", "https://api.openai.com/v1"));
	}

	private static StaticListableBeanFactory beans(boolean withAdapter) {
		StaticListableBeanFactory factory = new StaticListableBeanFactory();
		if (withAdapter) {
			factory.addBean("ai", new EnhancedCvExtractionPort() {

				@Override
				public AdapterInfo info() {
					return new AdapterInfo("x", "1", "1", "m");
				}

				@Override
				public List<RawSuggestion> extract(String reducedText, List<SkillInfo> catalog) {
					return List.of();
				}

			});
		}
		return factory;
	}

	@Test
	@DisplayName("009-11: encender la lectura mejorada sin adaptador de IA hace fallar el arranque")
	void t009_11_enablingWithoutAdapterFails() {
		CvConfig.CvAiStartupCheck check = new CvConfig.CvAiStartupCheck(properties(true),
				beans(false).getBeanProvider(EnhancedCvExtractionPort.class));

		assertThatThrownBy(check::check).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("talenti.ai.cv.enabled");
	}

	@Test
	@DisplayName("009-11: apagada no pide nada, y encendida con adaptador arranca")
	void t009_11_otherCombinationsStart() {
		assertThatCode(() -> new CvConfig.CvAiStartupCheck(properties(false),
				beans(false).getBeanProvider(EnhancedCvExtractionPort.class)).check()).doesNotThrowAnyException();
		assertThatCode(() -> new CvConfig.CvAiStartupCheck(properties(true),
				beans(true).getBeanProvider(EnhancedCvExtractionPort.class)).check()).doesNotThrowAnyException();
	}

}
