package com.talenti.github.client;

/**
 * El token que entrega GitHub al canjear el código (013-27). Vive solo en memoria durante la vuelta del inicio de
 * sesión: no se guarda en la base, la sesión, los registros ni las respuestas. Su {@code toString} no lo muestra.
 */
public final class AccessToken {

	private final String value;

	AccessToken(String value) {
		this.value = value;
	}

	String value() {
		return value;
	}

	@Override
	public String toString() {
		return "AccessToken[***]";
	}

}
