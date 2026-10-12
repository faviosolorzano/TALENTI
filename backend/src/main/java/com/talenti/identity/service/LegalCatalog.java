package com.talenti.identity.service;

import java.time.Instant;
import java.util.List;

/**
 * Los documentos legales de la versión vigente, tal como los entrega {@code GET /legal/current}
 * (001-17, 001-28).
 */
public record LegalCatalog(String version, Instant publishedAt, List<LegalDocument> documents) {

	/** Un documento: {@code key} es {@code terms}, {@code privacy}, {@code cookies} o {@code consent-account}. */
	public record LegalDocument(String key, String title, List<LegalSection> sections) {
	}

	/**
	 * Una sección. {@code heading} viene de un encabezado {@code ##}; el texto que precede al primer encabezado
	 * va en una sección con {@code heading} vacío. {@code body} es Markdown.
	 */
	public record LegalSection(String heading, String body) {
	}

}
