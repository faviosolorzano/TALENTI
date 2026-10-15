package com.talenti.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;

/** PDF hostiles INVENTADOS para las pruebas de denegación de servicio. Se generan en el momento. */
public final class HostilePdfs {

	private HostilePdfs() {
	}

	/**
	 * Formularios anidados (tipo "billion laughs"): cada nivel dibuja el anterior 10 veces, así que 28 niveles son
	 * 10^28 operaciones. Pesa unos pocos KB. Tiene texto de sobra antes de la bomba.
	 */
	public static byte[] nestedForms(int levels) {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.A4);
			document.addPage(page);
			PDFormXObject previous = null;
			for (int level = 0; level < levels; level++) {
				PDFormXObject form = new PDFormXObject(document);
				form.setBBox(new PDRectangle(0, 0, 100, 100));
				PDResources resources = new PDResources();
				if (previous != null) {
					resources.put(COSName.getPDFName("Prev"), previous);
				}
				form.setResources(resources);
				try (OutputStream out = form.getContentStream().createOutputStream()) {
					String content = previous == null ? "q Q\n" : "/Prev Do\n".repeat(10);
					out.write(content.getBytes(StandardCharsets.US_ASCII));
				}
				previous = form;
			}
			try (PDPageContentStream content = new PDPageContentStream(document, page)) {
				content.beginText();
				content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
				content.setLeading(12);
				content.newLineAtOffset(40, 780);
				for (int i = 0; i < 8; i++) {
					content.showText("Texto inventado de prueba para que el PDF tenga suficiente contenido legible " + i);
					content.newLine();
				}
				content.endText();
				if (previous != null) {
					content.drawForm(previous);
				}
			}
			return save(document);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Un flujo de contenido de {@code megabytes} MB de espacios, comprimido: pesa unos cientos de KB. */
	public static byte[] hugeCompressedStream(int megabytes) {
		try (PDDocument document = new PDDocument()) {
			PDPage page = new PDPage(PDRectangle.A4);
			document.addPage(page);
			PDStream stream = new PDStream(document);
			byte[] chunk = new byte[1024 * 1024];
			Arrays.fill(chunk, (byte) ' ');
			try (OutputStream out = stream.createOutputStream(COSName.FLATE_DECODE)) {
				for (int i = 0; i < megabytes; i++) {
					out.write(chunk);
				}
			}
			page.setContents(stream);
			return save(document);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static byte[] save(PDDocument document) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		document.save(out);
		return out.toByteArray();
	}

}
