package com.talenti.github.domain;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Piezas del inicio de sesión con GitHub (013-25, 013-30): el {@code state} de un solo uso, su hash (lo único que se
 * guarda) y la dirección de autorización, <strong>sin {@code scope}</strong>. Código puro, sin Spring.
 */
public final class OAuthState {

	/** 32 bytes al azar (013-25). */
	public static final int STATE_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	private OAuthState() {
	}

	/** Un {@code state} nuevo: 32 bytes de {@link SecureRandom} en Base64URL sin relleno (43 caracteres). */
	public static String newState() {
		byte[] bytes = new byte[STATE_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** SHA-256 en hexadecimal: lo que se guarda del {@code state} y del id de sesión. */
	public static String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 no disponible", e);
		}
	}

	/**
	 * {@code {oauthBaseUrl}/login/oauth/authorize?client_id&redirect_uri&state&allow_signup=false}. A propósito no
	 * lleva {@code scope}: sin él, GitHub concede solo lectura de la información pública (013-30).
	 */
	public static String authorizeUrl(String oauthBaseUrl, String clientId, String redirectUri, String state) {
		return trimSlash(oauthBaseUrl) + "/login/oauth/authorize?client_id=" + encode(clientId) + "&redirect_uri="
				+ encode(redirectUri) + "&state=" + encode(state) + "&allow_signup=false";
	}

	public static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	public static String trimSlash(String url) {
		return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
	}

}
