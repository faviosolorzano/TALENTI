package com.talenti.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;

/** 001-11: de dónde sale el origen que cuenta el límite de ingresos. */
class ClientOriginTests {

	private MockHttpServletRequest request(String realIp, String remoteAddr) {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr(remoteAddr);
		if (realIp != null) {
			request.addHeader("X-Real-IP", realIp);
		}
		return request;
	}

	@Test
	@DisplayName("001-11: una IPv4 válida en X-Real-IP es el origen (se recortan los espacios)")
	void t001_11_validIpv4HeaderIsTheOrigin() {
		assertThat(ClientOrigin.of(request("203.0.113.7", "172.18.0.5"))).isEqualTo("203.0.113.7");
		assertThat(ClientOrigin.of(request("  203.0.113.7 ", "172.18.0.5"))).isEqualTo("203.0.113.7");
	}

	@ParameterizedTest
	@ValueSource(strings = { "evil.example.com", "localhost", "1.2.3", "1.2.3.4.5", "999.1.1.1", "256.0.0.1",
			"1.2.3.4, 5.6.7.8", "010.1.1.1", "fe80::1%eth0", "::g", "1:2:3:4:5:6:7:8:9", "-1.2.3.4",
			"0x7f.0.0.1", "", "   ", "unknown" })
	@DisplayName("001-11: una cabecera que no es una IP literal se descarta y se usa la dirección de la conexión")
	void t001_11_invalidHeaderFallsBackToRemoteAddress(String header) {
		assertThat(ClientOrigin.of(request(header, "172.18.0.5"))).isEqualTo("172.18.0.5");
	}

	@Test
	@DisplayName("001-11: sin cabecera se usa la dirección de la conexión")
	void t001_11_noHeaderUsesRemoteAddress() {
		assertThat(ClientOrigin.of(request(null, "172.18.0.5"))).isEqualTo("172.18.0.5");
	}

	@Test
	@DisplayName("001-11: las direcciones IPv6 se agrupan por /64")
	void t001_11_ipv6IsGroupedByPrefix64() {
		String a = ClientOrigin.of(request("2001:db8:aaaa:bbbb:1:2:3:4", "172.18.0.5"));
		String sameBlock = ClientOrigin.of(request("2001:DB8:aaaa:bbbb:ffff::9", "172.18.0.5"));
		String otherBlock = ClientOrigin.of(request("2001:db8:aaaa:cccc:1:2:3:4", "172.18.0.5"));

		assertThat(a).isEqualTo("2001:0db8:aaaa:bbbb::/64");
		assertThat(sameBlock).isEqualTo(a);
		assertThat(otherBlock).isNotEqualTo(a).isEqualTo("2001:0db8:aaaa:cccc::/64");
	}

	@Test
	@DisplayName("001-11: una IPv6 mapeada a IPv4 cuenta como la IPv4; una conexión IPv6 también se agrupa")
	void t001_11_mappedAndRemoteIpv6() {
		assertThat(ClientOrigin.of(request("::ffff:203.0.113.7", "172.18.0.5"))).isEqualTo("203.0.113.7");
		assertThat(ClientOrigin.of(request(null, "2001:db8:1:2:3:4:5:6"))).isEqualTo("2001:0db8:0001:0002::/64");
		assertThat(ClientOrigin.of(request("::1", "172.18.0.5"))).isEqualTo("0000:0000:0000:0000::/64");
	}

}
