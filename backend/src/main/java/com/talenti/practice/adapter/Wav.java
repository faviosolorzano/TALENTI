package com.talenti.practice.adapter;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * WAV mínimo (PCM de 8 bits, mono, 8 kHz) para los simulados y las pruebas: un tono o silencio, y un fragmento
 * opcional {@code TXT } con el texto que el reconocedor simulado "escuchará". No es audio real de ninguna persona.
 */
public final class Wav {

	public static final int SAMPLE_RATE = 8000;

	private Wav() {
	}

	/** Tono (o silencio) de la duración pedida, con texto opcional y un fragmento extra opcional (marca de prueba). */
	public static byte[] of(int durationMs, boolean silent, String spokenText, String extraChunk) {
		int samples = (int) ((long) SAMPLE_RATE * durationMs / 1000);
		byte[] data = new byte[samples];
		for (int i = 0; i < samples; i++) {
			data[i] = silent ? (byte) 0x80 : (byte) (0x80 + (int) (60 * Math.sin(2 * Math.PI * 440 * i / SAMPLE_RATE)));
		}
		ByteArrayOutputStream chunks = new ByteArrayOutputStream();
		chunk(chunks, "fmt ", fmt());
		if (spokenText != null) {
			chunk(chunks, "TXT ", spokenText.getBytes(StandardCharsets.UTF_8));
		}
		if (extraChunk != null) {
			chunk(chunks, "junk", extraChunk.getBytes(StandardCharsets.UTF_8));
		}
		chunk(chunks, "data", data);
		byte[] body = chunks.toByteArray();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
		le32(out, 4 + body.length);
		out.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
		out.writeBytes(body);
		return out.toByteArray();
	}

	/** El texto del fragmento {@code TXT }, o nulo si no hay. */
	static String spokenText(byte[] wav) {
		byte[] chunk = chunk(wav, "TXT ");
		return chunk == null ? null : new String(chunk, StandardCharsets.UTF_8);
	}

	/** Si los datos son silencio (todas las muestras en el punto medio). */
	static boolean silent(byte[] wav) {
		byte[] data = chunk(wav, "data");
		if (data == null) {
			return false;
		}
		for (byte sample : data) {
			if (Math.abs((sample & 0xFF) - 0x80) > 2) {
				return false;
			}
		}
		return true;
	}

	static boolean isWav(byte[] bytes) {
		return bytes != null && bytes.length >= 12 && new String(bytes, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
				&& new String(bytes, 8, 4, StandardCharsets.US_ASCII).equals("WAVE");
	}

	private static byte[] chunk(byte[] wav, String id) {
		int pos = 12;
		while (pos + 8 <= wav.length) {
			String current = new String(wav, pos, 4, StandardCharsets.US_ASCII);
			int size = (wav[pos + 4] & 0xFF) | (wav[pos + 5] & 0xFF) << 8 | (wav[pos + 6] & 0xFF) << 16
					| (wav[pos + 7] & 0xFF) << 24;
			if (size < 0 || pos + 8 + (long) size > wav.length) {
				return null;
			}
			if (current.equals(id)) {
				byte[] result = new byte[size];
				System.arraycopy(wav, pos + 8, result, 0, size);
				return result;
			}
			pos += 8 + size + (size & 1);
		}
		return null;
	}

	private static byte[] fmt() {
		ByteArrayOutputStream fmt = new ByteArrayOutputStream();
		le16(fmt, 1); // PCM
		le16(fmt, 1); // mono
		le32(fmt, SAMPLE_RATE);
		le32(fmt, SAMPLE_RATE); // byteRate: 1 byte por muestra
		le16(fmt, 1); // blockAlign
		le16(fmt, 8); // bits por muestra
		return fmt.toByteArray();
	}

	private static void chunk(ByteArrayOutputStream out, String id, byte[] content) {
		out.writeBytes(id.getBytes(StandardCharsets.US_ASCII));
		le32(out, content.length);
		out.writeBytes(content);
		if ((content.length & 1) == 1) {
			out.write(0);
		}
	}

	private static void le16(ByteArrayOutputStream out, int value) {
		out.write(value & 0xFF);
		out.write((value >> 8) & 0xFF);
	}

	private static void le32(ByteArrayOutputStream out, int value) {
		le16(out, value & 0xFFFF);
		le16(out, (value >> 16) & 0xFFFF);
	}

}
