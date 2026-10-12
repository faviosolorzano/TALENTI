package com.talenti.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.mockito.Mockito;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

/**
 * 001-10 con solicitudes simultáneas: el intento se reserva antes de comparar la contraseña, así que el bloqueo
 * no se puede saltar lanzando muchas solicitudes a la vez. Se cuentan las comparaciones reales contra el hash de la
 * cuenta (el espía del codificador ve cada {@code matches}).
 */
@MockitoSpyBean(types = PasswordEncoder.class)
class LoginConcurrencyTests extends IdentityIntegrationTest {

	private static final String LOGIN = "/api/v1/auth/login";

	@RepeatedTest(3)
	@DisplayName("001-10: N solicitudes simultáneas con contraseñas distintas comparan como máximo 5 antes del bloqueo, y con la cuenta bloqueada ni la contraseña correcta entra")
	void t001_10_parallelAttemptsNeverExceedFiveChecks() throws Exception {
		RegisteredStudent student = registerStudent();
		String hash = jdbc.queryForObject("SELECT password_hash FROM app_user WHERE id = ?", String.class, student.id());
		int parallel = 24;

		// 1. Rafaga de contraseñas erróneas distintas (cada solicitud desde su propio origen, sin tocar el límite de IP).
		List<Response> wrong = parallel(parallel, i -> loginBody(student.email(), validPassword()));

		assertThat(wrong).allSatisfy(response -> assertThat(response.status()).isEqualTo(401));
		assertThat(comparisonsAgainst(hash)).as("comparaciones reales durante la ráfaga").isLessThanOrEqualTo(5);
		assertThat(jdbc.queryForObject("SELECT locked_until FROM app_user WHERE id = ?", Object.class, student.id()))
				.as("la cuenta quedó bloqueada").isNotNull();
		assertThat(count("SELECT failed_login_count FROM app_user WHERE id = ?", student.id())).isEqualTo(5);

		// 2. Otra ráfaga, ahora con la contraseña CORRECTA, durante el bloqueo: nadie entra y no se compara nada.
		int before = comparisonsAgainst(hash);
		List<Response> correct = parallel(10, i -> loginBody(student.email(), student.password()));

		assertThat(correct).allSatisfy(response -> {
			assertThat(response.status()).isEqualTo(401);
			assertThat(response.setCookies()).noneMatch(cookie -> cookie.startsWith("JSESSIONID"));
		});
		assertThat(comparisonsAgainst(hash)).isEqualTo(before);
		assertThat(jdbc.queryForObject("SELECT locked_until FROM app_user WHERE id = ?", Object.class, student.id()))
				.isNotNull();

		// 3. Pasado el bloqueo se puede ingresar y el contador se reinicia.
		clock.advance(Duration.ofMinutes(16));
		ApiClient later = client();
		assertThat(later.post(LOGIN, loginBody(student.email(), student.password())).status()).isEqualTo(200);
		assertThat(count("SELECT failed_login_count FROM app_user WHERE id = ?", student.id())).isZero();
	}

	/** Cuántas veces se comparó una contraseña contra este hash (el señuelo usa otro hash y no cuenta). */
	private int comparisonsAgainst(String hash) {
		return (int) Mockito.mockingDetails(passwordEncoder).getInvocations().stream()
				.filter(invocation -> invocation.getMethod().getName().equals("matches"))
				.filter(invocation -> hash.equals(invocation.getArgument(1)))
				.count();
	}

	private interface BodyFactory {

		Object body(int index);

	}

	/** Lanza {@code n} solicitudes de ingreso a la vez, cada una desde su propio cliente y origen. */
	private List<Response> parallel(int n, BodyFactory bodies) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(n);
		try {
			CountDownLatch ready = new CountDownLatch(n);
			CountDownLatch go = new CountDownLatch(1);
			List<Future<Response>> futures = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				ApiClient api = client();
				api.get("/api/v1/auth/csrf");
				Object body = bodies.body(i);
				Callable<Response> job = () -> {
					ready.countDown();
					go.await();
					return api.post(LOGIN, body);
				};
				futures.add(pool.submit(job));
			}
			ready.await();
			go.countDown();
			List<Response> responses = new ArrayList<>();
			for (Future<Response> future : futures) {
				responses.add(future.get());
			}
			return responses;
		}
		finally {
			pool.shutdownNow();
		}
	}

}
