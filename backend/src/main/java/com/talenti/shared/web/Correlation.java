package com.talenti.shared.web;

/** Clave del identificador de correlación en el MDC (la usa también el patrón de registro de application.yml). */
public final class Correlation {

	public static final String MDC_KEY = "correlationId";

	private Correlation() {
	}

}
