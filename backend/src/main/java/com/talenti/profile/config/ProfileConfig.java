package com.talenti.profile.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.talenti.profile.domain.CompletenessRule;
import com.talenti.profile.domain.ProfileRules;

/** Piezas del módulo profile que salen de la configuración. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProfileProperties.class)
class ProfileConfig {

	@Bean
	CompletenessRule completenessRule(ProfileProperties properties) {
		ProfileProperties.Weights weights = properties.completeness().weights();
		return new CompletenessRule(weights.academic(), weights.skills(), weights.availability(), weights.projects(),
				weights.location(), weights.about(), properties.completeness().minSkills(), properties.completeness().minModalities());
	}

	@Bean
	ProfileRules profileRules(ProfileProperties properties) {
		return new ProfileRules(properties.maxCycle(), properties.maxProjects());
	}

}
