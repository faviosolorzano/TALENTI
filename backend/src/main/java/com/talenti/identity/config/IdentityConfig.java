package com.talenti.identity.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.talenti.identity.domain.LoginAttemptPolicy;
import com.talenti.identity.domain.PasswordPolicy;

/** Piezas del módulo identity que salen de la configuración. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({ IdentityProperties.class, LegalProperties.class })
class IdentityConfig {

	/** Hash adaptativo (RNF-01): BCrypt con costo configurable. */
	@Bean
	PasswordEncoder passwordEncoder(IdentityProperties properties) {
		return new BCryptPasswordEncoder(properties.password().bcryptCost());
	}

	@Bean
	PasswordPolicy passwordPolicy(IdentityProperties properties) {
		return new PasswordPolicy(properties.password().minLength(), properties.password().maxLength());
	}

	@Bean
	LoginAttemptPolicy loginAttemptPolicy(IdentityProperties properties) {
		return new LoginAttemptPolicy(properties.lockout().maxFailedAttempts(), properties.lockout().duration());
	}

}
