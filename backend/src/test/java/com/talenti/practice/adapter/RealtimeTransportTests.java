package com.talenti.practice.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/** Comprueba el multipart real contra un servidor local; nunca contacta al proveedor. */
class RealtimeTransportTests {

	@Test
	void multipartDeclaresSdpAndJsonTypesWithoutPuttingKeyInBody() throws Exception {
		AtomicReference<String> body = new AtomicReference<>();
		AtomicReference<String> authorization = new AtomicReference<>();
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/calls", exchange -> {
			body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			exchange.getResponseHeaders().set("Location", "/calls/rtc_test");
			byte[] answer = "v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(201, answer.length);
			exchange.getResponseBody().write(answer);
			exchange.close();
		});
		server.start();
		try {
			RealtimeTransport.Response response = RealtimeTransport.http("test-only-key-marker").postMultipart(
					"http://127.0.0.1:" + server.getAddress().getPort() + "/calls",
					Map.of("sdp", "v=0\r\nm=audio", "session", "{\"type\":\"realtime\"}"), Duration.ofSeconds(5));
			assertThat(response.status()).isEqualTo(201);
			assertThat(body.get()).contains("name=\"sdp\"\r\nContent-Type: application/sdp\r\n\r\nv=0",
					"name=\"session\"\r\nContent-Type: application/json\r\n\r\n{\"type\":\"realtime\"}")
					.doesNotContain("test-only-key-marker");
			assertThat(authorization.get()).isEqualTo("Bearer test-only-key-marker");
			assertThat(response.location()).isEqualTo("/calls/rtc_test");
		}
		finally {
			server.stop(0);
		}
	}

}
