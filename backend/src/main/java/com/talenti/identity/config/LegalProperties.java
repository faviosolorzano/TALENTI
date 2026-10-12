package com.talenti.identity.config;

import java.time.Instant;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Versión vigente de los documentos legales ({@code talenti.legal.*}). Una sola versión para todo el
 * conjunto; los textos están en {@code src/main/resources/legal/<version>/}.
 *
 * @param version     por ejemplo {@code v1}
 * @param publishedAt fecha de publicación de esa versión
 */
@ConfigurationProperties(prefix = "talenti.legal")
public record LegalProperties(String version, Instant publishedAt) {
}
