package com.talenti.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;
import com.talenti.support.FakeGithub;

/**
 * 013-17, 013-21 y 013-27: los registros, la auditoría y las respuestas no contienen descripciones, temas, el
 * {@code code}, el {@code state}, el token de OAuth, el secreto de la OAuth App ni la clave del servidor. Se sube el
 * registro de la aplicación a TRACE y el de JDBC y transacciones a DEBUG, con marcas únicas en cada dato.
 *
 * <p>Fuera de esta prueba queda a propósito el nivel DEBUG/TRACE de {@code org.springframework.web} y
 * {@code org.springframework.security} (igual que en 004-14): esos registros del marco imprimen la URL con su cadena
 * de consulta, que en la vuelta lleva el {@code code} y el {@code state}. Ni dev ni prod los activan, y nginx registra
 * los accesos sin la cadena de consulta.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"talenti.demo-data.enabled=true", "talenti.github.token=" + GithubNeverLoggedTests.SERVER_TOKEN,
		"logging.level.com.talenti=TRACE", "logging.level.org.springframework.jdbc=DEBUG",
		"logging.level.org.springframework.transaction=TRACE" })
class GithubNeverLoggedTests extends GithubIntegrationTest {

	static final String SERVER_TOKEN = "ghp_servidorMarcaLog7Hq";

	@Test
	@DisplayName("013-17: ni descripciones, ni temas, ni code, ni state, ni token, ni secreto, ni clave aparecen en registros, auditoría o respuestas")
	void t013_17_nothingSensitiveIsLogged(CapturedOutput output) {
		String mark = "marcaGh" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
		FakeGithub.Account account = fake.account("registros")
				.repo("repo-marcado", "Descripcion " + mark, false, List.of("tema-" + mark.toLowerCase(), "docker"), "Java",
						100);
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		// Una vuelta fallida (código que GitHub no reconoce) y una correcta.
		String failedState = start(api);
		String failedCode = "codigoFallido" + mark;
		assertThat(outcome(callback(api, failedCode, failedState))).isEqualTo("oauth-failed");
		String state = start(api);
		String code = fake.authorize(account);
		String token = fake.tokenFor(code);
		assertThat(outcome(callback(api, code, state))).isEqualTo("linked");

		List<Response> responses = new java.util.ArrayList<>();
		responses.add(api.get(GITHUB));
		Response list = api.get(GITHUB + "/repositories");
		responses.add(select(api, List.of(repoId(list.json(), "repo-marcado"))));
		responses.add(api.get(GITHUB + "/suggestions"));
		responses.add(decide(api, decision("java", "ACCEPT", 2), decision("docker", "REJECT", null)));
		responses.add(api.post(GITHUB + "/refresh", null));
		fake.fail("/users/", 503, "{\"message\":\"" + mark + "\"}", Map.of());
		clock.advance(java.time.Duration.ofMinutes(11));
		responses.add(api.post(GITHUB + "/refresh", null));
		responses.add(api.delete(GITHUB));

		assertThat(responses).extracting(Response::status).containsExactly(200, 200, 200, 200, 200, 503, 204);
		String logs = output.getAll();
		assertThat(logs).as("registros").isNotEmpty().contains("GitHub respondió")
				.doesNotContain(mark).doesNotContain(mark.toLowerCase())
				.doesNotContain(code).doesNotContain(failedCode).doesNotContain(state).doesNotContain(failedState)
				.doesNotContain(token).doesNotContain(CLIENT_SECRET).doesNotContain(SERVER_TOKEN)
				.doesNotContain(student.email());
		for (String secret : List.of(code, state, token, CLIENT_SECRET, SERVER_TOKEN)) {
			assertThat(count("SELECT count(*) FROM audit_event WHERE details::text LIKE ?", "%" + secret + "%"))
					.isZero();
			assertThat(rowsContaining(secret)).as("en la base").isZero();
			for (Response response : responses) {
				assertThat(response.body()).doesNotContain(secret);
			}
		}
		assertThat(count("SELECT count(*) FROM audit_event WHERE details::text LIKE ?", "%" + mark + "%")).isZero();
		assertThat(list.body()).contains(mark);
	}

}
