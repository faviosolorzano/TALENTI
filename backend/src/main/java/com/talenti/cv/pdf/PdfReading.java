package com.talenti.cv.pdf;

import java.io.IOException;
import java.io.Writer;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * La lectura de texto de un PDF propiamente dicha, sin Spring. Corre SOLO dentro del proceso hijo
 * ({@link PdfChildMain}), que tiene memoria y tiempo acotados desde fuera (el proceso padre lo mata si se pasa).
 */
final class PdfReading {

	private PdfReading() {
	}

	/** Motivo de un PDF ilegible, sin contenido del archivo. */
	static final class Failure extends RuntimeException {

		private static final long serialVersionUID = 1L;

		private final transient CvUnreadableException.Reason reason;

		Failure(CvUnreadableException.Reason reason) {
			super(reason.name(), null, false, false);
			this.reason = reason;
		}

		CvUnreadableException.Reason reason() {
			return reason;
		}

	}

	record Result(String text, int pages) {
	}

	private static final class LimitReached extends IOException {

		private static final long serialVersionUID = 1L;

		LimitReached() {
			super("limit");
		}

	}

	static Result read(byte[] pdf, int maxPages, int maxTextChars, int minTextChars) {
		try (PDDocument document = Loader.loadPDF(pdf, "", null, null, IOUtils.createMemoryOnlyStreamCache())) {
			if (document.isEncrypted()) {
				throw new Failure(CvUnreadableException.Reason.ENCRYPTED);
			}
			int pages = document.getNumberOfPages();
			if (pages > maxPages) {
				throw new Failure(CvUnreadableException.Reason.TOO_MANY_PAGES);
			}
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setStartPage(1);
			stripper.setEndPage(Math.max(1, pages));
			BoundedWriter writer = new BoundedWriter(maxTextChars);
			try {
				stripper.writeText(document, writer);
			}
			catch (LimitReached reached) {
				// Se leyó lo permitido; el resto se ignora.
			}
			String text = writer.toString().strip();
			if (text.length() < minTextChars) {
				throw new Failure(CvUnreadableException.Reason.NO_TEXT);
			}
			return new Result(text, pages);
		}
		catch (InvalidPasswordException e) {
			throw new Failure(CvUnreadableException.Reason.ENCRYPTED);
		}
		catch (Failure e) {
			throw e;
		}
		catch (IOException | RuntimeException | StackOverflowError e) {
			throw new Failure(CvUnreadableException.Reason.CORRUPT);
		}
	}

	private static final class BoundedWriter extends Writer {

		private final StringBuilder out = new StringBuilder();

		private final int max;

		BoundedWriter(int max) {
			this.max = max;
		}

		@Override
		public void write(char[] buffer, int offset, int length) throws IOException {
			int room = max - out.length();
			if (length > room) {
				out.append(buffer, offset, Math.max(0, room));
				throw new LimitReached();
			}
			out.append(buffer, offset, length);
		}

		@Override
		public void flush() {
		}

		@Override
		public void close() {
		}

		@Override
		public String toString() {
			return out.toString();
		}

	}

}
