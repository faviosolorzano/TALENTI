package com.talenti.cv.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import com.talenti.cv.config.CvProperties;
import com.talenti.support.SampleCv;
import com.talenti.support.TestPdfs;

/** 009-05 y 009-06: lectura de texto de un PDF verdadero, con límites y sin OCR. Los PDF se generan en la prueba. */
class PdfTextExtractorTests {

	private final PdfTextExtractor extractor = extractor(100_000);

	private static PdfTextExtractor extractor(int maxTextChars) {
		return new PdfTextExtractor(new CvProperties(DataSize.ofMegabytes(2), 10, 200, maxTextChars,
				Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofDays(7), 5, 10, "classpath:cv/careers.txt", 2, 20));
	}

	private static CvUnreadableException.Reason reasonOf(Runnable action) {
		return org.assertj.core.api.Assertions.catchThrowableOfType(CvUnreadableException.class, action::run).reason();
	}

	@Test
	@DisplayName("009-05: un PDF verdadero con texto devuelve el texto y las páginas")
	void t009_05_validPdf() {
		PdfTextExtractor.Extracted extracted = extractor.extract(SampleCv.pdf("MARCAUNO"));

		assertThat(extracted.pages()).isEqualTo(1);
		assertThat(extracted.text()).contains("Instituto Tecsup").contains("Java, Spring Boot").contains("MARCAUNO");
	}

	@Test
	@DisplayName("009-05: acentos y eñes del español se leen bien")
	void t009_05_spanishCharacters() {
		byte[] pdf = TestPdfs.of(List.of("Educación: Diseño y Desarrollo de Software", "Ingeniería de Computación",
				"Programación en Java con atención al detalle y a las pruebas de aceptación en el año 2026",
				"Proyecto: Sistema de gestión para una pequeña empresa de servicios turísticos del Perú"));

		assertThat(extractor.extract(pdf).text()).contains("Educación").contains("Diseño").contains("Programación");
	}

	@Test
	@DisplayName("009-05: algo que no empieza con %PDF- es NOT_A_PDF, aunque diga ser un PDF")
	void t009_05_notAPdf() {
		assertThat(reasonOf(() -> extractor.extract("Esto es texto plano con extensión engañosa".getBytes(StandardCharsets.UTF_8))))
				.isEqualTo(CvUnreadableException.Reason.NOT_A_PDF);
		assertThat(reasonOf(() -> extractor.extract(new byte[0]))).isEqualTo(CvUnreadableException.Reason.NOT_A_PDF);
		assertThat(reasonOf(() -> extractor.extract(new byte[] { 'M', 'Z', 1, 2, 3, 4, 5 })))
				.isEqualTo(CvUnreadableException.Reason.NOT_A_PDF);
	}

	@Test
	@DisplayName("009-05: un PDF cortado a la mitad es CORRUPT")
	void t009_05_corrupt() {
		assertThat(reasonOf(() -> extractor.extract(TestPdfs.corrupt())))
				.isEqualTo(CvUnreadableException.Reason.CORRUPT);
		assertThat(reasonOf(() -> extractor.extract("%PDF-1.7\nbasura sin estructura".getBytes(StandardCharsets.US_ASCII))))
				.isEqualTo(CvUnreadableException.Reason.CORRUPT);
	}

	@Test
	@DisplayName("009-05: un PDF cifrado es ENCRYPTED")
	void t009_05_encrypted() {
		assertThat(reasonOf(() -> extractor.extract(TestPdfs.encrypted(SampleCv.lines("M")))))
				.isEqualTo(CvUnreadableException.Reason.ENCRYPTED);
	}

	@Test
	@DisplayName("009-05: más de 10 páginas es TOO_MANY_PAGES; con 10 se lee")
	void t009_05_tooManyPages() {
		assertThat(extractor.extract(TestPdfs.withPages(10)).pages()).isEqualTo(10);
		assertThat(reasonOf(() -> extractor.extract(TestPdfs.withPages(11))))
				.isEqualTo(CvUnreadableException.Reason.TOO_MANY_PAGES);
	}

	@Test
	@DisplayName("009-05: un PDF escaneado (sin texto) o con menos de 200 caracteres es NO_TEXT")
	void t009_05_noText() {
		assertThat(reasonOf(() -> extractor.extract(TestPdfs.scanned()))).isEqualTo(CvUnreadableException.Reason.NO_TEXT);
		assertThat(reasonOf(() -> extractor.extract(TestPdfs.of("Solo una línea corta"))))
				.isEqualTo(CvUnreadableException.Reason.NO_TEXT);
	}

	@Test
	@DisplayName("009-06: el texto se corta en la longitud máxima y no es un error")
	void t009_06_textIsCapped() {
		PdfTextExtractor.Extracted extracted = extractor(500).extract(TestPdfs.withPages(3));

		assertThat(extracted.text().length()).isLessThanOrEqualTo(500).isGreaterThan(200);
	}

	@Test
	@DisplayName("009-06: el resultado no se imprime con su contenido")
	void t009_06_toStringHasNoContent() {
		PdfTextExtractor.Extracted extracted = extractor.extract(SampleCv.pdf("MARCADOS"));

		assertThat(extracted.toString()).doesNotContain("MARCADOS").doesNotContain("Java");
		assertThatThrownBy(() -> extractor.extract(TestPdfs.scanned())).hasMessageNotContaining("MARCADOS");
	}

}
