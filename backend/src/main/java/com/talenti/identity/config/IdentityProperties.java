package com.talenti.identity.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros del módulo identity ({@code talenti.identity.*}). Viven en configuración, no en el código;
 * los valores por defecto son los de la spec 001 (sección 10).
 */
@ConfigurationProperties(prefix = "talenti.identity")
public record IdentityProperties(
		@DefaultValue Password password,
		@DefaultValue Lockout lockout,
		@DefaultValue LoginRateLimit loginRateLimit) {

	/** Política de contraseña y costo de BCrypt (bajo en las pruebas para que sean rápidas). */
	public record Password(
			@DefaultValue("8") int minLength,
			@DefaultValue("64") int maxLength,
			@DefaultValue("12") int bcryptCost) {
	}

	/** Bloqueo por intentos fallidos consecutivos, por cuenta (RF-04). */
	public record Lockout(
			@DefaultValue("5") int maxFailedAttempts,
			@DefaultValue("15m") Duration duration) {
	}

	/** Límite de solicitudes de ingreso por origen (RNF-05). */
	public record LoginRateLimit(
			@DefaultValue("10") int maxRequests,
			@DefaultValue("1m") Duration window) {
	}

}
