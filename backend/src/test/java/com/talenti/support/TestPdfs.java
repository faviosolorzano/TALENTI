package com.talenti.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** PDF pequeños e INVENTADOS, generados en el momento. Ningún CV real vive en el repositorio. */
public final class TestPdfs {

	private static final int LINES_PER_PAGE = 50;

	private TestPdfs() {
	}

	/** Un PDF con esas líneas de texto (varias páginas si hace falta). */
	public static byte[] of(List<String> lines) {
		try (PDDocument document = new PDDocument()) {
			addText(document, lines);
			return save(document);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static byte[] of(String... lines) {
		return of(List.of(lines));
	}

	/** Un PDF con exactamente {@code pages} páginas, cada una con texto suficiente. */
	public static byte[] withPages(int pages) {
		List<String> lines = new ArrayList<>();
		for (int page = 0; page < pages; page++) {
			for (int i = 0; i < LINES_PER_PAGE; i++) {
				lines.add("Pagina " + (page + 1) + " linea " + i + " texto de relleno inventado para la prueba");
			}
		}
		return of(lines);
	}

	/** Un PDF "escaneado": solo dibujo, sin texto. */
	public static byte[] scanned() {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.A4);
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.addRect(50, 50, 400, 600);
				content.fill();
			}
			return save(document);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Un PDF cifrado con contraseña de apertura. */
	public static byte[] encrypted(List<String> lines) {
		try (PDDocument document = new PDDocument()) {
			addText(document, lines);
			StandardProtectionPolicy policy = new StandardProtectionPolicy("duena-demo", "abre-demo",
					new AccessPermission());
			policy.setEncryptionKeyLength(128);
			document.protect(policy);
			return save(document);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Empieza como un PDF pero está truncado a la mitad. */
	public static byte[] corrupt() {
		byte[] good = of(lines(60, "relleno"));
		byte[] half = new byte[good.length / 3];
		System.arraycopy(good, 0, half, 0, half.length);
		return half;
	}

	public static List<String> lines(int count, String text) {
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			lines.add(text + " " + i + " texto de relleno inventado");
		}
		return lines;
	}

	private static void addText(PDDocument document, List<String> lines) throws IOException {
		PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
		for (int from = 0; from < lines.size() || from == 0; from += LINES_PER_PAGE) {
			PDPage page = new PDPage(PDRectangle.A4);
			document.addPage(page);
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.beginText();
				content.setFont(font, 10);
				content.setLeading(13);
				content.newLineAtOffset(40, 800);
				for (int i = from; i < Math.min(lines.size(), from + LINES_PER_PAGE); i++) {
					content.showText(lines.get(i));
					content.newLine();
				}
				content.endText();
			}
			if (lines.isEmpty()) {
				break;
			}
		}
	}

	private static byte[] save(PDDocument document) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		document.save(out);
		return out.toByteArray();
	}

}
