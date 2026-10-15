package com.talenti.cv.pdf;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.talenti.cv.config.CvProperties;

import jakarta.annotation.PreDestroy;

/**
 * Lee SOLO el texto de un PDF (009-05, 009-06). El archivo nunca se ejecuta ni se abre para la persona: no se
 * interpretan scripts, formularios ni archivos incrustados (solo se recorren las páginas para sacar texto). La
 * lectura corre en un PROCESO HIJO acotado (otra JVM con memoria máxima pequeña, ver {@link PdfChildMain}) que se
 * mata al vencer el tiempo: cancelar un hilo no detiene a PDFBox con un PDF hostil (formularios anidados), un
 * proceso sí. Además hay un máximo global de lecturas simultáneas. Sin OCR: un PDF escaneado sin texto es
 * {@code NO_TEXT}.
 *
 * <p>Nunca registra el contenido ni el nombre del archivo; los fallos se traducen a un motivo.
 */
@Component
public class PdfTextExtractor {

	private static final Logger log = LoggerFactory.getLogger(PdfTextExtractor.class);

	private static final byte[] SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);

	/** Memoria máxima del proceso hijo: un PDF de 2 MB legítimo cabe de sobra. */
	static final String CHILD_HEAP = "-Xmx192m";

	/** Tiempo extra para arrancar la JVM hija; el límite de lectura (parseTimeout) se cuenta aparte. */
	private static final long STARTUP_GRACE_MILLIS = 30_000;

	private static final long ACQUIRE_WAIT_MILLIS = 2_000;

	/** Texto extraído; no se imprime en registros. */
	public record Extracted(String text, int pages) {

		@Override
		public String toString() {
			return "Extracted[pages=" + pages + ", chars=" + text.length() + "]";
		}

	}

	private final CvProperties properties;

	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	/** Máximo global de PDF leyéndose a la vez (cada uno es una JVM aparte). */
	private final Semaphore slots;

	public PdfTextExtractor(CvProperties properties) {
		this.properties = properties;
		this.slots = new Semaphore(Math.max(1, properties.maxConcurrentExtractions()));
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
	 * @throws CvBusyException       503 si ya hay el máximo de lecturas simultáneas
	 */
	public Extracted extract(byte[] pdf) {
		if (!hasPdfSignature(pdf)) {
			throw new CvUnreadableException(CvUnreadableException.Reason.NOT_A_PDF);
		}
		boolean acquired;
		try {
			acquired = slots.tryAcquire(ACQUIRE_WAIT_MILLIS, TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CvBusyException();
		}
		if (!acquired) {
			throw new CvBusyException();
		}
		try {
			return runChild(pdf);
		}
		finally {
			slots.release();
		}
	}

	/** Arranca la JVM hija; reintenta unas veces porque el sistema operativo puede negar un arranque pasajero. */
	private Process start() {
		for (int attempt = 1; attempt <= 5; attempt++) {
			try {
				return new ProcessBuilder(command()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
			}
			catch (IOException | RuntimeException e) {
				if (attempt < 5) {
					try {
						Thread.sleep(200L * attempt);
					}
					catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						break;
					}
				}
			}
		}
		log.warn("No se pudo arrancar el proceso hijo de lectura de PDF");
		throw new CvUnreadableException(CvUnreadableException.Reason.CORRUPT);
	}

	private Extracted runChild(byte[] pdf) {
		Process process = start();
		CountDownLatch ready = new CountDownLatch(1);
		Future<Extracted> task = executor.submit(() -> exchange(process, pdf, ready));
		try {
			// El arranque de la JVM hija (que puede tardar según la máquina) no cuenta contra el tiempo de lectura:
			// este solo empieza cuando el hijo avisa que está listo. Un PDF hostil no puede alargar el arranque.
			if (!ready.await(STARTUP_GRACE_MILLIS, TimeUnit.MILLISECONDS) && !task.isDone()) {
				throw new TimeoutException();
			}
			return task.get(properties.parseTimeout().toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e) {
			log.warn("La lectura de un PDF superó el tiempo máximo y su proceso fue terminado");
			throw new CvUnreadableException(CvUnreadableException.Reason.CORRUPT);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CvUnreadableException(CvUnreadableException.Reason.CORRUPT);
		}
		catch (ExecutionException e) {
			if (e.getCause() instanceof CvUnreadableException unreadable) {
				throw unreadable;
			}
			log.warn("El proceso hijo de lectura de PDF terminó sin respuesta ({})",
					e.getCause() == null ? "?" : e.getCause().getClass().getSimpleName());
			throw new CvUnreadableException(CvUnreadableException.Reason.CORRUPT);
		}
		finally {
			task.cancel(true);
			process.destroyForcibly();
			try {
				process.waitFor(2, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/** Entrega el PDF al hijo y lee su respuesta (acotada). Corre en un hilo aparte para poder vencer por tiempo. */
	private Extracted exchange(Process process, byte[] pdf, CountDownLatch ready) throws IOException {
		executor.execute(() -> {
			try (OutputStream in = process.getOutputStream()) {
				in.write(pdf);
			}
			catch (IOException ignored) {
				// El hijo pudo morir antes de leer todo: la lectura de abajo lo reporta.
			}
		});
		long maxBytes = 4L * properties.maxTextChars() + 64;
		try (InputStream raw = process.getInputStream(); DataInputStream out = new DataInputStream(raw)) {
			if (out.read() != PdfChildMain.READY) {
				ready.countDown();
				throw new CvUnreadableException(CvUnreadableException.Reason.CORRUPT);
			}
			ready.countDown();
			int status = out.read();
			if (status == PdfChildMain.OK) {
				int pages = out.readInt();
				int length = out.readInt();
				if (length < 0 || length > maxBytes) {
					throw new CvUnreadableException(CvUnreadableException.Reason.CORRUPT);
				}
				String text = new String(out.readNBytes(length), StandardCharsets.UTF_8);
				return new Extracted(text, pages);
			}
			if (status == PdfChildMain.FAILED) {
				int ordinal = out.read();
				CvUnreadableException.Reason[] reasons = CvUnreadableException.Reason.values();
				if (ordinal >= 0 && ordinal < reasons.length) {
					throw new CvUnreadableException(reasons[ordinal]);
				}
			}
			throw new CvUnreadableException(CvUnreadableException.Reason.CORRUPT);
		}
	}

	/** El comando de la JVM hija: mismo Java y mismas clases que el servidor, con memoria acotada. */
	private List<String> command() {
		List<String> command = new ArrayList<>();
		File bin = new File(System.getProperty("java.home"), "bin");
		File exe = new File(bin, "java.exe");
		command.add((exe.isFile() ? exe : new File(bin, "java")).getPath());
		command.addAll(List.of(CHILD_HEAP, "-Xss1m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
				"-XX:+ExitOnOutOfMemoryError", "-XX:-UsePerfData", "-Djava.awt.headless=true", "-Dfile.encoding=UTF-8",
				"-Duser.timezone=UTC"));
		String classPath = System.getProperty("java.class.path");
		if (classPath.endsWith(".jar") && !classPath.contains(File.pathSeparator)) {
			// Servidor empaquetado (Spring Boot): el lanzador de propiedades arranca el programa dentro del jar.
			command.addAll(List.of("-Dloader.main=" + PdfChildMain.class.getName(), "-cp", classPath,
					"org.springframework.boot.loader.launch.PropertiesLauncher"));
		}
		else {
			command.addAll(List.of("-cp", classPath, PdfChildMain.class.getName()));
		}
		command.add(Integer.toString(properties.maxPages()));
		command.add(Integer.toString(properties.maxTextChars()));
		command.add(Integer.toString(properties.minTextChars()));
		return command;
	}

}
