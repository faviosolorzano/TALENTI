package com.talenti.matching.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * La huella del caché (015-12): SHA-256 de lo enviado a la IA ({@link ExplanationRequest#json()}) más la versión de la
 * fórmula, la de las instrucciones y el modelo. Si cambia el perfil, la vacante, la fórmula, las instrucciones o el
 * modelo, cambia la huella y la explicación guardada deja de valer.
 */
public final class ExplanationFingerprint {

	private ExplanationFingerprint() {
	}

	/** 64 caracteres hexadecimales en minúsculas. */
	public static String of(ExplanationRequest request, String formulaVersion, String promptVersion, String model) {
		String material = request.json() + "\nformula=" + nullToEmpty(formulaVersion) + "\nprompt="
				+ nullToEmpty(promptVersion) + "\nmodel=" + nullToEmpty(model);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(material.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 no está disponible", e);
		}
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

}
