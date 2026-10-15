package com.talenti.cv.pdf;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Programa del proceso hijo que lee un PDF (009-05). Recibe el PDF por la entrada estándar y los límites como
 * argumentos ({@code maxPages maxTextChars minTextChars}); responde por la salida estándar: un byte de estado
 * ({@code 0} = texto, {@code 1} = motivo) y los datos. No escribe nada en disco ni imprime el contenido en
 * ningún otro sitio. El proceso padre lo limita en memoria ({@code -Xmx}) y en tiempo (lo mata si se pasa), así
 * que un PDF hostil (formularios anidados, flujos comprimidos enormes) solo puede consumir ese espacio y ese tiempo.
 */
public final class PdfChildMain {

	/** Aviso de que la JVM ya arrancó: desde aquí corre el tiempo máximo de lectura. */
	static final int READY = 2;

	static final int OK = 0;

	static final int FAILED = 1;

	private PdfChildMain() {
	}

	public static void main(String[] args) throws IOException {
		// La salida estándar es SOLO para la respuesta: cualquier registro de una librería va a la salida de error.
		java.io.PrintStream protocol = System.out;
		System.setOut(System.err);
		protocol.write(READY);
		protocol.flush();
		int maxPages = Integer.parseInt(args[0]);
		int maxTextChars = Integer.parseInt(args[1]);
		int minTextChars = Integer.parseInt(args[2]);
		byte[] pdf = System.in.readAllBytes();
		DataOutputStream out = new DataOutputStream(new BufferedOutputStream(protocol));
		try {
			PdfReading.Result result = PdfReading.read(pdf, maxPages, maxTextChars, minTextChars);
			byte[] text = result.text().getBytes(StandardCharsets.UTF_8);
			out.writeByte(OK);
			out.writeInt(result.pages());
			out.writeInt(text.length);
			out.write(text);
		}
		catch (PdfReading.Failure failure) {
			out.writeByte(FAILED);
			out.writeByte(failure.reason().ordinal());
		}
		out.flush();
		System.exit(0);
	}

}
