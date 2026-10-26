package com.talenti.github.client;

/**
 * El canje del código devolvió {@code error} (por ejemplo código vencido o {@code redirect_uri} distinta, 013-29).
 * No guarda el texto de GitHub: solo el hecho.
 */
public class OAuthExchangeFailedException extends RuntimeException {

	public OAuthExchangeFailedException() {
		super("El canje del código de GitHub devolvió un error");
	}

}
