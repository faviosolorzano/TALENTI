package com.talenti.github.client;

import static org.assertj.core.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.talenti.github.config.GithubProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.json.JsonMapper;

@Timeout(10)
class GithubResponseRegressionTests {

	private HttpServer server;
	private ExecutorService executor;
	private GithubClient client;
	private final AtomicInteger calls = new AtomicInteger();
	private final CountDownLatch release = new CountDownLatch(1);
	private volatile String body = "[]";
	private volatile int status = 200;
	private volatile boolean stall;

	@BeforeEach
	void setup() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		executor = Executors.newVirtualThreadPerTaskExecutor();
		server.setExecutor(executor);
		server.createContext("/", this::respond);
		server.start();
		var properties = new GithubProperties("http://127.0.0.1:" + server.getAddress().getPort(), "2022-11-28", "",
				null, false, 1, 6, 10, Duration.ofHours(24), Duration.ofMinutes(10), 5, Duration.ofSeconds(1),
				Duration.ofMillis(400), 30, ZoneId.of("America/Lima"), "");
		client = new GithubClient(properties, GithubClient.budget(properties, Clock.systemUTC()), JsonMapper.builder().build(),
				Clock.systemUTC());
	}

	private void respond(HttpExchange exchange) throws java.io.IOException {
		calls.incrementAndGet();
		try {
			exchange.sendResponseHeaders(status, 0);
			if (stall) {
				exchange.getResponseBody().write('[');
				exchange.getResponseBody().flush();
				release.await(5, TimeUnit.SECONDS);
				exchange.getResponseBody().write(']');
			}
			else {
				exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		finally {
			exchange.close();
		}
	}

	@AfterEach
	void cleanup() {
		release.countDown();
		server.stop(0);
		executor.shutdownNow();
	}

	@Test
	void t013_18_bodyStalledAfterHeadersTimesOutWithoutRetry() {
		stall = true;
		long started = System.nanoTime();
		assertThatThrownBy(() -> client.repositories("current", 123, null, null))
				.isInstanceOf(GithubUnavailableException.class);
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void t013_18_oversizedChunkedBodyIsRejected() {
		body = " ".repeat(GithubClient.MAX_BODY_BYTES + 1);
		assertThatThrownBy(() -> client.repositories("current", 123, null, null))
				.isInstanceOf(GithubUnavailableException.class);
		assertThat(calls.get()).isEqualTo(1);
	}

	@Test
	void t013_05_otherOwnerInRepositoryResponseIsRejected() {
		body = "[{\"id\":999,\"name\":\"project\",\"html_url\":\"https://github.com/current/project\",\"owner\":{\"id\":456}}]";
		assertThatThrownBy(() -> client.repositories("current", 123, null, null))
				.isInstanceOf(GithubUnavailableException.class);
	}

	@Test
	void t013_05_missingOwnerIsRejected() {
		body = "[{\"id\":999,\"name\":\"project\",\"html_url\":\"https://github.com/current/project\"}]";
		assertThatThrownBy(() -> client.repositories("current", 123, null, null))
				.isInstanceOf(GithubUnavailableException.class);
	}

	@Test
	void t013_06_validOwnerAndBodyAreAccepted() {
		body = "[{\"id\":999,\"name\":\"project\",\"html_url\":\"https://github.com/current/project\",\"owner\":{\"id\":123}}]";
		assertThat(client.repositories("current", 123, null, null).value()).singleElement()
				.satisfies(repo -> assertThat(repo.id()).isEqualTo(999));
	}

	@Test
	void t013_18_redirectIsNotFollowed() {
		status = 301;
		assertThatThrownBy(() -> client.repositories("current", 123, null, null))
				.isInstanceOf(GithubUnavailableException.class);
		assertThat(calls.get()).isEqualTo(1);
	}
}
