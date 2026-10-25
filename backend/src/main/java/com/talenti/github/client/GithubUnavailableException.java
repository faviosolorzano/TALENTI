package com.talenti.github.client;

/**
 * GitHub no respondió como se esperaba (013-20): 5xx, tiempo de espera, red o una respuesta con forma inesperada. El
 * mensaje solo describe el tipo de falla.
 */
public class GithubUnavailableException extends RuntimeException {

	public GithubUnavailableException(String reason) {
		super(reason);
	}

}
