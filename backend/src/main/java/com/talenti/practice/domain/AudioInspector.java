package com.talenti.practice.domain;

import java.util.Optional;

/**
 * Reconoce el audio de un turno POR SU CONTENIDO, no por el nombre ni por el tipo que declara el navegador (012-07), y
 * estima su duración leyendo solo las cabeceras del formato (sin decodificar):
 * <ul>
 * <li>WAV: {@code RIFF....WAVE}; duración exacta por {@code byteRate} y el tamaño de {@code data}.</li>
 * <li>Ogg (Opus o Vorbis): {@code OggS}; duración por la posición de la última página.</li>
 * <li>WebM: cabecera EBML; elemento {@code Duration} si existe o, si no (lo normal al grabar con el navegador), el
 * tiempo del último {@code Cluster} (cota inferior).</li>
 * <li>MP4: caja {@code ftyp}; duración de {@code mvhd}.</li>
 * <li>MPEG: {@code ID3} o una cabecera de trama; duración estimada por la tasa de bits de la primera trama.</li>
 * </ul>
 * Código puro: nunca guarda ni registra el audio.
 */
public final class AudioInspector {

	public enum Format {

		WEBM("audio/webm"), OGG("audio/ogg"), MP4("audio/mp4"), MPEG("audio/mpeg"), WAV("audio/wav");

		private final String mime;

		Format(String mime) {
			this.mime = mime;
		}

		public String mime() {
			return mime;
		}

	}

	private AudioInspector() {
	}

	public static Optional<Format> detect(byte[] b) {
		if (b == null || b.length < 12) {
			return Optional.empty();
		}
		if (ascii(b, 0, "RIFF") && ascii(b, 8, "WAVE")) {
			return Optional.of(Format.WAV);
		}
		if (ascii(b, 0, "OggS")) {
			return Optional.of(Format.OGG);
		}
		if (u8(b, 0) == 0x1A && u8(b, 1) == 0x45 && u8(b, 2) == 0xDF && u8(b, 3) == 0xA3) {
			return Optional.of(Format.WEBM);
		}
		if (ascii(b, 4, "ftyp")) {
			return Optional.of(Format.MP4);
		}
		if (ascii(b, 0, "ID3") || (u8(b, 0) == 0xFF && (u8(b, 1) & 0xE0) == 0xE0)) {
			return Optional.of(Format.MPEG);
		}
		return Optional.empty();
	}

	/** Duración en milisegundos si se puede saber por las cabeceras; vacío si no. */
	public static Optional<Integer> durationMs(byte[] b, Format format) {
		try {
			Long ms = switch (format) {
				case WAV -> wav(b);
				case OGG -> ogg(b);
				case WEBM -> webm(b);
				case MP4 -> mp4(b);
				case MPEG -> mpeg(b);
			};
			return ms == null || ms < 0 ? Optional.empty() : Optional.of((int) Math.min(Integer.MAX_VALUE, ms));
		}
		catch (RuntimeException malformed) {
			return Optional.empty();
		}
	}

	// ------------------------------------------------------------------------------------------ formatos

	private static Long wav(byte[] b) {
		long byteRate = 0;
		int pos = 12;
		while (pos + 8 <= b.length) {
			long size = le32(b, pos + 4);
			if (ascii(b, pos, "fmt ")) {
				byteRate = le32(b, pos + 8 + 8);
			}
			else if (ascii(b, pos, "data")) {
				long available = b.length - (pos + 8L);
				long dataBytes = size > available ? available : size;
				return byteRate <= 0 ? null : dataBytes * 1000 / byteRate;
			}
			pos += 8 + (int) Math.min(size + (size & 1), Integer.MAX_VALUE - pos - 8);
		}
		return null;
	}

	private static Long ogg(byte[] b) {
		long rate = indexOf(b, "OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0) >= 0 ? 48000 : 0;
		if (rate == 0) {
			int vorbis = indexOf(b, new byte[] { 0x01, 'v', 'o', 'r', 'b', 'i', 's' }, 0);
			if (vorbis >= 0 && vorbis + 16 <= b.length) {
				rate = le32(b, vorbis + 12);
			}
		}
		int last = lastIndexOf(b, "OggS".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		if (rate <= 0 || last < 0 || last + 14 > b.length) {
			return null;
		}
		long granule = le64(b, last + 6);
		return granule < 0 ? null : granule * 1000 / rate;
	}

	private static Long mp4(byte[] b) {
		int mvhd = indexOf(b, "mvhd".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0);
		if (mvhd < 0 || mvhd + 32 > b.length) {
			return null;
		}
		int version = u8(b, mvhd + 4);
		long timescale = version == 1 ? be32(b, mvhd + 24) : be32(b, mvhd + 16);
		long duration = version == 1 ? be64(b, mvhd + 28) : be32(b, mvhd + 20);
		return timescale <= 0 ? null : duration * 1000 / timescale;
	}

	private static Long webm(byte[] b) {
		long scaleNs = 1_000_000;
		int scale = indexOf(b, new byte[] { 0x2A, (byte) 0xD7, (byte) 0xB1 }, 0);
		if (scale >= 0) {
			long[] vint = vint(b, scale + 3);
			scaleNs = uint(b, (int) (scale + 3 + vint[1]), (int) vint[0]);
		}
		int duration = indexOf(b, new byte[] { 0x44, (byte) 0x89 }, 0);
		if (duration >= 0) {
			long[] vint = vint(b, duration + 2);
			int start = (int) (duration + 2 + vint[1]);
			double value = vint[0] == 4 ? Float.intBitsToFloat((int) be32(b, start))
					: vint[0] == 8 ? Double.longBitsToDouble(be64(b, start)) : -1;
			if (value >= 0) {
				return (long) (value * scaleNs / 1_000_000);
			}
		}
		int cluster = lastIndexOf(b, new byte[] { 0x1F, 0x43, (byte) 0xB6, 0x75 });
		if (cluster < 0) {
			return null;
		}
		long[] clusterSize = vint(b, cluster + 4);
		int pos = (int) (cluster + 4 + clusterSize[1]);
		if (pos < b.length && u8(b, pos) == 0xE7) {
			long[] vint = vint(b, pos + 1);
			long timecode = uint(b, (int) (pos + 1 + vint[1]), (int) vint[0]);
			return timecode * scaleNs / 1_000_000;
		}
		return null;
	}

	private static final int[] MPEG1_L3 = { 0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320 };

	private static final int[] MPEG2_L3 = { 0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160 };

	private static Long mpeg(byte[] b) {
		int pos = 0;
		if (ascii(b, 0, "ID3") && b.length > 10) {
			pos = 10 + ((u8(b, 6) & 0x7F) << 21 | (u8(b, 7) & 0x7F) << 14 | (u8(b, 8) & 0x7F) << 7 | (u8(b, 9) & 0x7F));
		}
		while (pos + 4 <= b.length && !(u8(b, pos) == 0xFF && (u8(b, pos + 1) & 0xE0) == 0xE0)) {
			pos++;
		}
		if (pos + 4 > b.length) {
			return null;
		}
		int version = (u8(b, pos + 1) >> 3) & 0x03;
		int index = (u8(b, pos + 2) >> 4) & 0x0F;
		if (index == 0 || index == 15) {
			return null;
		}
		int kbps = version == 3 ? MPEG1_L3[index] : MPEG2_L3[index];
		return (b.length - pos) * 8L / kbps;
	}

	// ------------------------------------------------------------------------------------------ bytes

	private static int u8(byte[] b, int i) {
		return b[i] & 0xFF;
	}

	private static boolean ascii(byte[] b, int offset, String text) {
		if (offset + text.length() > b.length) {
			return false;
		}
		for (int i = 0; i < text.length(); i++) {
			if (b[offset + i] != (byte) text.charAt(i)) {
				return false;
			}
		}
		return true;
	}

	private static long le32(byte[] b, int i) {
		return (long) u8(b, i) | (long) u8(b, i + 1) << 8 | (long) u8(b, i + 2) << 16 | (long) u8(b, i + 3) << 24;
	}

	private static long le64(byte[] b, int i) {
		return le32(b, i) | le32(b, i + 4) << 32;
	}

	private static long be32(byte[] b, int i) {
		return (long) u8(b, i) << 24 | (long) u8(b, i + 1) << 16 | (long) u8(b, i + 2) << 8 | u8(b, i + 3);
	}

	private static long be64(byte[] b, int i) {
		return be32(b, i) << 32 | be32(b, i + 4);
	}

	private static long uint(byte[] b, int start, int length) {
		long value = 0;
		for (int i = 0; i < length; i++) {
			value = value << 8 | u8(b, start + i);
		}
		return value;
	}

	/** Entero de largo variable de EBML: {valor, bytes que ocupa}. */
	private static long[] vint(byte[] b, int i) {
		int first = u8(b, i);
		int length = Integer.numberOfLeadingZeros(first) - 23;
		if (length < 1 || length > 8) {
			throw new IllegalArgumentException("vint");
		}
		long value = first & (0xFF >> length);
		for (int k = 1; k < length; k++) {
			value = value << 8 | u8(b, i + k);
		}
		return new long[] { value, length };
	}

	private static int indexOf(byte[] b, byte[] pattern, int from) {
		outer: for (int i = from; i <= b.length - pattern.length; i++) {
			for (int k = 0; k < pattern.length; k++) {
				if (b[i + k] != pattern[k]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

	private static int lastIndexOf(byte[] b, byte[] pattern) {
		outer: for (int i = b.length - pattern.length; i >= 0; i--) {
			for (int k = 0; k < pattern.length; k++) {
				if (b[i + k] != pattern[k]) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}

}
