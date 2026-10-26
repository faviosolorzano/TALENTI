package com.talenti.practice.port;

/**
 * Audio de DORA ya sintetizado. Vive solo en memoria (en el flujo del turno o, para las preguntas, mientras dure la
 * sesión, 012-10); nunca se guarda. {@code toString} no muestra el contenido.
 *
 * @param mime    por ejemplo {@code audio/mpeg} o {@code audio/wav}
 * @param audioMs duración aproximada
 */
public record SpokenAudio(String mime, byte[] data, int audioMs) {

	@Override
	public String toString() {
		return "SpokenAudio[mime=" + mime + ", bytes=" + (data == null ? 0 : data.length) + "]";
	}

}
