package com.talenti.github.client;

/** GitHub respondió 404: el repositorio o la cuenta ya no existe, o dejó de ser público (013-09). */
public class GithubNotFoundException extends RuntimeException {

	public GithubNotFoundException() {
		super("GitHub respondió 404");
	}

}
