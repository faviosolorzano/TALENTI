package com.talenti.practice.domain;

import java.nio.charset.StandardCharsets;

/**
 * Validación mínima de la oferta SDP que manda el navegador (012-43). El SDP lleva direcciones IP: nunca se guarda ni
 * se registra; aquí solo se mira su tamaño y su forma. Código puro.
 */
public final class SdpOffer {

	/** Resultado de la validación. */
	public enum Check {

		OK,
		/** Más de {@code max-sdp}: 413. */
		TOO_LARGE,
		/** Sin {@code v=0} al inicio o sin línea {@code m=audio}: 422 {@code sdp-invalid}. */
		INVALID

	}

	private SdpOffer() {
	}

	public static Check check(String sdp, long maxBytes) {
		if (sdp == null || sdp.isBlank()) {
			return Check.INVALID;
		}
		if (sdp.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
			return Check.TOO_LARGE;
		}
		String[] lines = sdp.split("\\r?\\n");
		if (!lines[0].strip().equals("v=0")) {
			return Check.INVALID;
		}
		boolean audio = false;
		for (String line : lines) {
			if (line.startsWith("m=audio ")) {
				audio = true;
			}
			// Un SDP es texto: sin caracteres de control (salvo tabulador y retorno de carro).
			for (int i = 0; i < line.length(); i++) {
				char c = line.charAt(i);
				if (c < 0x20 && c != '\t' && c != '\r') {
					return Check.INVALID;
				}
			}
		}
		return audio ? Check.OK : Check.INVALID;
	}

}
