package com.talenti.github.config;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Parámetros del módulo github ({@code talenti.github.*}, spec 013, sección 5). Todo vive en configuración; los
 * valores por defecto son los de la spec. Las claves ({@code token}, {@code oauth.client-secret}) llegan solo por
 * variables de entorno y nunca se imprimen ({@link #toString()}).
 *
 * @param baseUrl          API de GitHub (por defecto {@code https://api.github.com})
 * @param apiVersion       valor de {@code X-GitHub-Api-Version}
 * @param token            clave de servidor opcional ({@code GITHUB_TOKEN}); vacía = sin autenticación (013-21)
 * @param includeForks     mostrar forks (duda 2: no)
 * @param maxPages         páginas de 100 repositorios que se leen
 * @param maxSelected      repositorios que se pueden elegir
 * @param minLanguageShare porcentaje mínimo de un lenguaje para sugerirlo (013-10)
 * @param cacheTtl         vigencia de lo leído de GitHub (013-07)
 * @param refreshInterval  tiempo mínimo entre dos "actualizar" de la misma persona (013-09)
 * @param reserveCalls     llamadas que se dejan sin usar antes del límite de GitHub (013-19)
 * @param requestsPerMinute límite general por persona en {@code /github} (013-23)
 * @param zone             zona horaria para decir la hora de reintento ("a las 15:40")
 */
@ConfigurationProperties(prefix = "talenti.github")
public record GithubProperties(
		@DefaultValue("https://api.github.com") String baseUrl,
		@DefaultValue("2022-11-28") String apiVersion,
		@DefaultValue("") String token,
		@DefaultValue OAuth oauth,
		@DefaultValue("false") boolean includeForks,
		@DefaultValue("1") int maxPages,
		@DefaultValue("6") int maxSelected,
		@DefaultValue("10") double minLanguageShare,
		@DefaultValue("24h") Duration cacheTtl,
		@DefaultValue("10m") Duration refreshInterval,
		@DefaultValue("5") int reserveCalls,
		@DefaultValue("3s") Duration connectTimeout,
		@DefaultValue("5s") Duration readTimeout,
		@DefaultValue("30") int requestsPerMinute,
		@DefaultValue("America/Lima") ZoneId zone,
		@DefaultValue("classpath:github/technology-map.properties") String technologyMap) {

	public GithubProperties {
		token = token == null ? "" : token.strip();
	}

	public boolean hasToken() {
		return !token.isEmpty();
	}

	/**
	 * Inicio de sesión con GitHub (OAuth App, 013-24 a 013-30). Apagado por defecto.
	 *
	 * @param clientId     {@code GITHUB_CLIENT_ID}
	 * @param clientSecret {@code GITHUB_CLIENT_SECRET}; nunca se registra ni se devuelve
	 * @param baseUrl      dónde vive la autorización y el canje (por defecto {@code https://github.com})
	 * @param redirectUri  la vuelta registrada en la OAuth App
	 * @param returnUrl    página del frontend a la que se vuelve al terminar
	 * @param stateTtl     vigencia del {@code state}
	 */
	public record OAuth(
			@DefaultValue("false") boolean enabled,
			@DefaultValue("") String clientId,
			@DefaultValue("") String clientSecret,
			@DefaultValue("https://github.com") String baseUrl,
			@DefaultValue("http://localhost:3000/api/v1/github/oauth/callback") String redirectUri,
			@DefaultValue("http://localhost:3000/perfil") String returnUrl,
			@DefaultValue("10m") Duration stateTtl) {

		public OAuth {
			clientId = clientId == null ? "" : clientId.strip();
			clientSecret = clientSecret == null ? "" : clientSecret.strip();
			redirectUri = redirectUri == null ? "" : redirectUri.strip();
		}

		@Override
		public String toString() {
			return "OAuth[enabled=" + enabled + ", clientId=" + (clientId.isEmpty() ? "(vacío)" : "(definido)")
					+ ", clientSecret=" + (clientSecret.isEmpty() ? "(vacío)" : "(definido)") + "]";
		}

	}

	@Override
	public String toString() {
		return "GithubProperties[baseUrl=" + baseUrl + ", token=" + (hasToken() ? "(definido)" : "(vacío)") + ", oauth="
				+ oauth + "]";
	}

}
