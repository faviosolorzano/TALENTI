package com.talenti.identity.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.talenti.identity.config.LegalProperties;
import com.talenti.identity.service.LegalCatalog.LegalDocument;
import com.talenti.identity.service.LegalCatalog.LegalSection;
import com.talenti.shared.web.FieldViolation;

/**
 * Documentos legales de la versión vigente (RN-16). Los textos están en archivos versionados
 * ({@code legal/<version>/*.es.md}) y se leen una sola vez al arrancar: si falta alguno, la aplicación no arranca.
 * Cada archivo tiene un título {@code # } y secciones {@code ## encabezado}.
 */
@Service
public class LegalDocumentService {

	private static final Logger log = LoggerFactory.getLogger(LegalDocumentService.class);

	/** Clave pública de cada documento y su archivo. */
	private static final Map<String, String> FILES = Map.of(
			"terms", "terms.es.md",
			"privacy", "privacy.es.md",
			"cookies", "cookies.es.md",
			"consent-account", "consent-account.es.md");

	/** Orden en que se entregan. */
	private static final List<String> ORDER = List.of("terms", "privacy", "cookies", "consent-account");

	private final LegalCatalog catalog;

	public LegalDocumentService(LegalProperties properties) {
		List<LegalDocument> documents = new ArrayList<>();
		Map<String, List<String>> unfinished = new LinkedHashMap<>();
		for (String key : ORDER) {
			String markdown = read(properties.version(), FILES.get(key));
			documents.add(parse(key, markdown));
			List<String> placeholders = LegalPlaceholders.find(markdown);
			if (!placeholders.isEmpty()) {
				unfinished.put(key, placeholders);
			}
		}
		if (!unfinished.isEmpty()) {
			// Un solo aviso, sin detener el arranque: la demo corre con borradores (RNF-08 pide revisión legal).
			log.warn("Los documentos legales {} tienen marcadores sin completar: {}. Complétalos y haz la revisión legal "
					+ "antes de usar Talenti con personas reales.", properties.version(), unfinished);
		}
		this.catalog = new LegalCatalog(properties.version(), properties.publishedAt(), List.copyOf(documents));
	}

	public LegalCatalog current() {
		return catalog;
	}

	public String currentVersion() {
		return catalog.version();
	}

	/** Reglas de la aceptación: la casilla marcada y la versión presente (001-02). */
	public List<FieldViolation> validateAcceptance(Boolean legalAccepted, String legalVersion) {
		List<FieldViolation> violations = new ArrayList<>();
		if (!Boolean.TRUE.equals(legalAccepted)) {
			violations.add(FieldViolation.of("legalAccepted", "must-be-accepted"));
		}
		if (legalVersion == null || legalVersion.isBlank()) {
			violations.add(FieldViolation.of("legalVersion", "required"));
		}
		return violations;
	}

	/** 409 si la versión enviada ya no es la vigente (001-03). */
	public void requireCurrentVersion(String legalVersion) {
		if (!catalog.version().equals(legalVersion)) {
			throw new LegalVersionOutdatedException();
		}
	}

	private static String read(String version, String file) {
		Resource resource = new ClassPathResource("legal/" + version + "/" + file);
		if (!resource.exists()) {
			throw new IllegalStateException("Falta el documento legal legal/" + version + "/" + file);
		}
		try (var in = resource.getInputStream()) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException("No se pudo leer el documento legal " + file, e);
		}
	}

	/** Separa el título y las secciones; el cuerpo de cada sección se conserva en Markdown. */
	static LegalDocument parse(String key, String markdown) {
		String title = key;
		List<LegalSection> sections = new ArrayList<>();
		String heading = "";
		StringBuilder body = new StringBuilder();
		for (String line : markdown.replace("\r\n", "\n").split("\n", -1)) {
			if (line.startsWith("## ")) {
				addSection(sections, heading, body);
				heading = line.substring(3).strip();
				body = new StringBuilder();
			}
			else if (line.startsWith("# ") && heading.isEmpty() && sections.isEmpty() && body.isEmpty()) {
				title = line.substring(2).strip();
			}
			else {
				body.append(line).append('\n');
			}
		}
		addSection(sections, heading, body);
		return new LegalDocument(key, title, List.copyOf(sections));
	}

	private static void addSection(List<LegalSection> sections, String heading, StringBuilder body) {
		String text = body.toString().strip();
		if (!heading.isEmpty() || !text.isEmpty()) {
			sections.add(new LegalSection(heading, text));
		}
	}

}
