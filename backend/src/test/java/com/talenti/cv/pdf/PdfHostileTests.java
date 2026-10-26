package com.talenti.cv.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import com.talenti.cv.config.CvProperties;
import com.talenti.support.HostilePdfs;
import com.talenti.support.SampleCv;

/**
 * 009-05 (endurecimiento): un PDF hostil no deja CPU ni memoria ocupadas. La lectura corre en un proceso hijo que se
 * mata al vencer el tiempo; hay un máximo de lecturas simultáneas.
 */
class PdfHostileTests {

	private static PdfTextExtractor extractor(int maxConcurrent) {
		return extractor(maxConcurrent, Duration.ofSeconds(3));
	}

	private static PdfTextExtractor extractor(int maxConcurrent, Duration timeout) {
		return new PdfTextExtractor(new CvProperties(DataSize.ofMegabytes(2), 10, 200, 100_000, timeout,
				Duration.ofSeconds(15), Duration.ofDays(7), 5, 10, "classpath:cv/careers.txt", maxConcurrent, 20));
	}

	private static long childProcesses() {
		return ProcessHandle.current().descendants()
				.filter(handle -> handle.info().commandLine().orElse("").contains(PdfChildMain.class.getName()))
				.filter(ProcessHandle::isAlive).count();
	}

	private static void awaitNoChildren() throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5_000;
		while (childProcesses() > 0 && System.currentTimeMillis() < deadline) {
			Thread.sleep(100);
		}
		assertThat(childProcesses()).as("procesos hijos vivos").isZero();
	}

	@Test
	@DisplayName("009-05: un PDF de pocos KB con formularios anidados (bomba) da CORRUPT al vencer el tiempo y no deja ningún proceso ni CPU ocupados")
	void t009_05_nestedFormsBombIsKilled() throws Exception {
		byte[] bomb = HostilePdfs.nestedForms(28);
		assertThat(bomb.length).as("el PDF pesa muy poco").isLessThan(60_000);
		PdfTextExtractor extractor = extractor(2);

		long start = System.nanoTime();
		CvUnreadableException failure = org.assertj.core.api.Assertions.catchThrowableOfType(CvUnreadableException.class,
				() -> extractor.extract(bomb));
		long seconds = Duration.ofNanos(System.nanoTime() - start).toSeconds();

		assertThat(failure).isNotNull();
		assertThat(failure.reason()).isEqualTo(CvUnreadableException.Reason.CORRUPT);
		assertThat(seconds).as("termina cerca del tiempo máximo, no se queda colgado").isLessThan(15);
		awaitNoChildren();
		// El servidor sigue sano: un PDF verdadero se lee después. Con más margen que la bomba (3 s): en un equipo cargado
		// solo arrancar el proceso hijo puede pasar de 3 s, y eso no es lo que esta prueba mide.
		assertThat(extractor(2, Duration.ofSeconds(15)).extract(SampleCv.pdf("MARCAHOSTIL")).text()).contains("MARCAHOSTIL");
	}

	@Test
	@DisplayName("009-05: un flujo comprimido enorme (200 MB de espacios en pocos KB) se rechaza sin agotar la memoria del servidor ni dejar procesos")
	void t009_05_hugeCompressedStream() throws Exception {
		byte[] pdf = HostilePdfs.hugeCompressedStream(200);
		assertThat(pdf.length).isLessThan(2 * 1024 * 1024);
		PdfTextExtractor extractor = extractor(2);

		CvUnreadableException failure = org.assertj.core.api.Assertions.catchThrowableOfType(CvUnreadableException.class,
				() -> extractor.extract(pdf));

		assertThat(failure).isNotNull();
		assertThat(failure.reason()).isIn(CvUnreadableException.Reason.CORRUPT, CvUnreadableException.Reason.NO_TEXT);
		awaitNoChildren();
	}

	@Test
	@DisplayName("009-05: con el máximo de lecturas simultáneas ocupado, otra responde 503 cv-busy y, al liberarse, vuelve a funcionar")
	void t009_05_concurrencyCap() throws Exception {
		PdfTextExtractor extractor = extractor(1, Duration.ofSeconds(10));
		byte[] bomb = HostilePdfs.nestedForms(28);
		CompletableFuture<Throwable> running = CompletableFuture.supplyAsync(() -> {
			try {
				extractor.extract(bomb);
				return null;
			}
			catch (RuntimeException e) {
				return e;
			}
		});
		Thread.sleep(1_000);

		assertThatThrownBy(() -> extractor.extract(SampleCv.pdf("OTRA"))).isInstanceOf(CvBusyException.class)
				.satisfies(e -> assertThat(((CvBusyException) e).getStatus().value()).isEqualTo(503));
		assertThat(running.get()).isInstanceOf(CvUnreadableException.class);
		awaitNoChildren();
		assertThat(extractor.extract(SampleCv.pdf("OTRA")).text()).contains("OTRA");
	}

}
