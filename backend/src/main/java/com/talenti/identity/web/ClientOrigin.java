package com.talenti.identity.web;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Origen de una solicitud, para el límite de ingresos.
 *
 * <p><strong>Supuesto:</strong> el backend solo es alcanzable a través de nginx (docker-compose no publica su
 * puerto), y nginx reemplaza la cabecera {@code X-Real-IP} con {@code $remote_addr}, la dirección real de quien
 * se conectó (frontend/nginx.conf). Por eso aquí se confía en esa cabecera. Si el backend se expusiera directo,
 * cualquiera podría falsificarla y saltarse el límite: en ese caso hay que quitar esta confianza.
 *
 * <p><strong>Detrás de otro proxy o balanceador</strong> (por ejemplo un balanceador de la nube delante de nginx),
 * {@code $remote_addr} sería la dirección de ese proxy y todos los visitantes compartirían un solo contador. Hay
 * que configurar en nginx {@code real_ip_header} y {@code set_real_ip_from} (solo con los rangos del proxy de
 * confianza) para que {@code $remote_addr} sea la del visitante.
 *
 * <p>El valor de la cabecera se valida como dirección IP <em>literal</em> con un análisis estricto, sin resolver
 * nombres (nunca se consulta DNS). Si no es válido se usa la dirección de la conexión. Las direcciones IPv6 se
 * agrupan por /64, para que quien controla un bloque entero no obtenga un contador nuevo por dirección.
 */
final class ClientOrigin {

	static final String HEADER = "X-Real-IP";

	private static final Pattern IPV4 = Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})");

	/** Solo los caracteres de una dirección IPv6 literal (sin zona "%" ni nombres). */
	private static final Pattern IPV6_CHARS = Pattern.compile("[0-9a-fA-F:.]{2,45}");

	private ClientOrigin() {
	}

	static String of(HttpServletRequest request) {
		String header = request.getHeader(HEADER);
		if (header != null) {
			Optional<String> fromHeader = normalize(header.strip());
			if (fromHeader.isPresent()) {
				return fromHeader.get();
			}
		}
		String remote = request.getRemoteAddr();
		return normalize(remote).orElse(remote);
	}

	/**
	 * La clave de origen de una dirección IP literal, o vacío si no lo es: IPv4 en forma canónica; IPv6 reducida
	 * a su prefijo /64.
	 */
	static Optional<String> normalize(String value) {
		if (value == null || value.isEmpty()) {
			return Optional.empty();
		}
		var ipv4 = IPV4.matcher(value);
		if (ipv4.matches()) {
			int[] octets = new int[4];
			for (int i = 0; i < 4; i++) {
				String octet = ipv4.group(i + 1);
				octets[i] = Integer.parseInt(octet);
				// Sin ceros a la izquierda: "010" se prestaría a leerse como octal en otros sistemas.
				if (octets[i] > 255 || (octet.length() > 1 && octet.startsWith("0"))) {
					return Optional.empty();
				}
			}
			return Optional.of(octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3]);
		}
		if (value.indexOf(':') < 0 || !IPV6_CHARS.matcher(value).matches()) {
			return Optional.empty();
		}
		try {
			// Tiene ":" y solo caracteres de una dirección: Java la trata como literal y no consulta DNS.
			InetAddress address = InetAddress.getByName(value);
			if (address instanceof Inet4Address v4) {
				// ::ffff:a.b.c.d es la misma máquina que a.b.c.d
				return Optional.of(v4.getHostAddress());
			}
			byte[] bytes = ((Inet6Address) address).getAddress();
			return Optional.of(String.format("%02x%02x:%02x%02x:%02x%02x:%02x%02x::/64", bytes[0], bytes[1], bytes[2],
					bytes[3], bytes[4], bytes[5], bytes[6], bytes[7]));
		}
		catch (UnknownHostException | ClassCastException e) {
			return Optional.empty();
		}
	}

}
