package com.talenti.identity.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.talenti.identity.domain.LoginAttemptPolicy;
import com.talenti.identity.domain.PasswordPolicy;
import com.talenti.identity.service.RateLimiter;

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

	/** Cubo de límite del ingreso (10 solicitudes por minuto por origen). */
	@Bean
	RateLimiter loginRateLimiter(IdentityProperties properties, Clock clock) {
		return new RateLimiter(properties.loginRateLimit().maxRequests(), properties.loginRateLimit().window(), clock);
	}

	/** Cubo de límite del registro, independiente del ingreso. */
	@Bean
	RateLimiter registerRateLimiter(IdentityProperties properties, Clock clock) {
		return new RateLimiter(properties.registerRateLimit().maxRequests(), properties.registerRateLimit().window(),
				clock);
	}

}
