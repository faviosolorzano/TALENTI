package com.talenti.cv.pdf;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import com.talenti.cv.config.CvProperties;

import jakarta.annotation.PreDestroy;

/**
 * Lee SOLO el texto de un PDF (009-05, 009-06). El archivo nunca se ejecuta ni se abre para la persona: no se
 * interpretan scripts, formularios ni archivos incrustados (solo se recorren las páginas para sacar texto), y todo
 * tiene límites: páginas, memoria (el archivo ya viene acotado a 2 MB y se lee en memoria), tiempo (5 s) y longitud
 * del texto (100 000 caracteres). Sin OCR: un PDF escaneado sin texto es {@code NO_TEXT}.
 *
 * <p>Nunca registra el contenido ni el nombre del archivo; las excepciones de la librería se traducen a un motivo.
 */
@Component
public class PdfTextExtractor {

	private static final byte[] SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

	/** Texto extraído; no se imprime en registros. */
	public record Extracted(String text, int pages) {

		@Override
		public String toString() {
			return "Extracted[pages=" + pages + ", chars=" + text.length() + "]";
		}

	}

	/** Se corta la lectura al llegar al máximo de caracteres. */
	private static final class LimitReached extends IOException {

		private static final long serialVersionUID = 1L;

		LimitReached() {
			super("limit");
		}

	}

	private final CvProperties properties;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public PdfTextExtractor(CvProperties properties) {
		this.properties = properties;
	}

	@PreDestroy
	void shutdown() {
		executor.shutdownNow();
	}

	/** Si los primeros bytes son la firma de un PDF ({@code %PDF-}); el nombre y el tipo declarado no cuentan. */
	public static boolean hasPdfSignature(byte[] bytes) {
		if (bytes == null || bytes.length < SIGNATURE.length) {
			return false;
		}
		for (int i = 0; i < SIGNATURE.length; i++) {
			if (bytes[i] != SIGNATURE[i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * @throws CvUnreadableException 422 con el motivo si no es un PDF legible con texto
	 */
	public Extracted extract(byte[] pdf) {
		if (!hasPdfSignature(pdf)) {
			throw new CvUnreadableException(CvUnreadableException.Reason.NOT_A_PDF);
		}
		Future<Extracted> task = executor.submit(() -> read(pdf));
		try {
			return task.get(properties.parseTimeout().toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			task.cancel(true);
			throw new CvUnreadableException(CvUnreadableException.Reason.CORRUPT);
		}
		catch (InterruptedException e) {
			task.cancel(true);
			Thread.currentThread().interrupt();
			throw new CvUnreadableException(CvUnreadableException.Reason.CORRUPT);
		}
		catch (ExecutionException e) {
			if (e.getCause() instanceof CvUnreadableException unreadable) {
				throw unreadable;
			}
			throw new CvUnreadableException(CvUnreadableException.Reason.CORRUPT);
		}
	}

	private Extracted read(byte[] pdf) {
		try (PDDocument document = Loader.loadPDF(pdf)) {
			if (document.isEncrypted()) {
				throw new CvUnreadableException(CvUnreadableException.Reason.ENCRYPTED);
			}
			int pages = document.getNumberOfPages();
			if (pages > properties.maxPages()) {
				throw new CvUnreadableException(CvUnreadableException.Reason.TOO_MANY_PAGES);
			}
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setStartPage(1);
			stripper.setEndPage(Math.max(1, pages));
			BoundedWriter writer = new BoundedWriter(properties.maxTextChars());
			try {
				stripper.writeText(document, writer);
			}
			catch (LimitReached reached) {
				// Se leyó lo permitido; el resto se ignora.
			}
			String text = writer.toString().strip();
			if (text.length() < properties.minTextChars()) {
				throw new CvUnreadableException(CvUnreadableException.Reason.NO_TEXT);
			}
			return new Extracted(text, pages);
		}
		catch (InvalidPasswordException e) {
			throw new CvUnreadableException(CvUnreadableException.Reason.ENCRYPTED);
		}
		catch (CvUnreadableException e) {
			throw e;
		}
		catch (IOException | RuntimeException | StackOverflowError | OutOfMemoryError e) {
			throw new CvUnreadableException(CvUnreadableException.Reason.CORRUPT);
		}
	}

	/** Acumula texto hasta un máximo y luego corta la lectura. */
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
