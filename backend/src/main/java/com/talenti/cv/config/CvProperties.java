package com.talenti.cv.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Parámetros del lector de CV ({@code talenti.cv.*}, spec 009 sección 5). Los valores por defecto son los de la spec.
 *
 * @param maxSize              tamaño máximo del PDF (también limita el multipart de Spring)
 * @param maxPages             páginas máximas
 * @param minTextChars         texto mínimo para que el PDF cuente como legible (sin OCR)
 * @param maxTextChars         texto máximo que se lee de un PDF
 * @param parseTimeout         tiempo máximo para leer el PDF
 * @param analysisTimeout      tiempo máximo de la lectura básica
 * @param retention            cuánto vive un análisis antes de expirar
 * @param dailyLimit           análisis nuevos por persona en 24 horas
 * @param originLimitPerMinute solicitudes de subida y lectura por minuto por origen
 * @param careersResource      archivo con el diccionario de carreras
 */
@ConfigurationProperties(prefix = "talenti.cv")
public record CvProperties(
		@DefaultValue("2MB") DataSize maxSize,
		@DefaultValue("10") int maxPages,
		@DefaultValue("200") int minTextChars,
		@DefaultValue("100000") int maxTextChars,
		@DefaultValue("5s") Duration parseTimeout,
		@DefaultValue("15s") Duration analysisTimeout,
		@DefaultValue("7d") Duration retention,
		@DefaultValue("5") int dailyLimit,
		@DefaultValue("10") int originLimitPerMinute,
		@DefaultValue("classpath:cv/careers.txt") String careersResource,
		@DefaultValue("2") int maxConcurrentExtractions,
		@DefaultValue("20") int failedAttemptsPerDay) {
}
