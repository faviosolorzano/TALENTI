package com.talenti.identity.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Origen de una solicitud, para el límite de ingresos.
 *
 * <p><strong>Supuesto:</strong> el backend solo es alcanzable a través de nginx (docker-compose no publica su
 * puerto), y nginx reemplaza la cabecera {@code X-Real-IP} con {@code $remote_addr}, la dirección real de quien
 * se conectó (frontend/nginx.conf). Por eso aquí se confía en esa cabecera. Si el backend se expusiera directo,
 * cualquiera podría falsificarla y saltarse el límite: en ese caso hay que quitar esta confianza. Sin la
 * cabecera se usa la dirección de la conexión.
 */
final class ClientOrigin {

	static final String HEADER = "X-Real-IP";

	private static final int MAX_LENGTH = 45; // una dirección IPv6 en texto

	private ClientOrigin() {
	}

	static String of(HttpServletRequest request) {
		String header = request.getHeader(HEADER);
		if (header != null) {
			String value = header.strip();
			if (!value.isEmpty() && value.length() <= MAX_LENGTH) {
				return value;
			}
		}
		return request.getRemoteAddr();
	}

}
