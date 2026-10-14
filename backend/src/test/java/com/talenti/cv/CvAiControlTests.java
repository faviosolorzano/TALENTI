package com.talenti.cv;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.talenti.cv.service.CvAnalysisService;
import com.talenti.cv.service.CvReadService;
import com.talenti.cv.service.ReadResult;
import com.talenti.shared.ai.AiLevel;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;
import com.talenti.support.SampleCv;
import com.talenti.support.TestPdfs;

import tools.jackson.databind.JsonNode;

/**
 * Control de consumo de IA (spec 009, sección 3.1): 009-25 a 009-33. La lectura mejorada se enciende con un adaptador de
 * IA SIMULADO con contador de llamadas; ninguna prueba hace una llamada de red real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"talenti.ai.cv.enabled=true", "talenti.ai.cv.model=modelo-simulado", "talenti.ai.cv.timeout=2s" })
@Import(CountingAiAdapter.Configuration.class)
class CvAiControlTests extends CvIntegrationTest {

	private static final ZoneId LIMA = ZoneId.of("America/Lima");

	@Autowired
	private CountingAiAdapter ai;

	@Autowired
	private CvReadService reads;

	@Autowired
	private CvAnalysisService service;

	@BeforeEach
	void resetAdapter() {
		ai.reset();
	}

	private static String readsPath(JsonNode analysis) {
		return CV + "/analyses/" + analysis.get("id").asString() + "/reads";
	}

	private Response enhance(ApiClient api, JsonNode analysis) {
		return api.post(readsPath(analysis), Map.of("mode", "ENHANCED"));
	}

	// ------------------------------------------------------------------------------------------ 009-25

	@Test
	@DisplayName("009-25: abrir pantallas, consultar el estado y volver a ingresar no hacen ninguna lectura ni llaman a la IA")
	void t009_25_openingScreensNeverReads() {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();

		for (int i = 0; i < 3; i++) {
			api.get(PROFILE);
			api.get(CV + "/usage");
			api.get(CV + "/analyses/current");
			api.get("/api/v1/vacancies");
			api.get("/api/v1/auth/me");
			api.get("/api/v1/legal/current");
		}
		assertThat(api.post("/api/v1/auth/login", loginBody(student.email(), student.password())).status()).isEqualTo(200);

		assertThat(ai.calls.get()).isZero();
		assertThat(analysisRows(student.id())).isZero();
		assertThat(usageRows(student.id())).isZero();
	}

	@Test
	@DisplayName("009-25: consultar el análisis una y otra vez no relee ni cambia la lectura; la básica no llama a la IA ni usa cupo")
	void t009_25_pollingDoesNotRead() {
		RegisteredStudent student = registerStudent();
		JsonNode analysis = uploadSampleAndWait(student.api());
		UUID readId = jdbc.queryForObject("SELECT current_read_id FROM cv_analysis WHERE user_id = ?", UUID.class,
				student.id());

		for (int i = 0; i < 10; i++) {
			assertThat(student.api().get(CV + "/analyses/current").json()).isEqualTo(student.api()
					.get(CV + "/analyses/current").json());
		}

		assertThat(jdbc.queryForObject("SELECT current_read_id FROM cv_analysis WHERE user_id = ?", UUID.class,
				student.id())).isEqualTo(readId);
		assertThat(ai.calls.get()).isZero();
		assertThat(usageRows(student.id())).as("la lectura básica es gratis y no se anota").isZero();
		assertThat(analysis.get("source").asString()).isEqualTo("RULES");
	}

	// ------------------------------------------------------------------------------------------ 009-26

	@Test
	@DisplayName("009-26: el mismo archivo con el análisis abierto devuelve ese análisis (reused) sin leer de nuevo ni llamar a la IA")
	void t009_26_sameFileOpenAnalysisIsReused() {
		RegisteredStudent student = registerStudent();
		byte[] pdf = SampleCv.pdf("MARCAREUSE");
		Response first = upload(student.api(), pdf, "ENHANCED");
		JsonNode analysis = awaitDone(student.api());
		assertThat(ai.calls.get()).isEqualTo(1);

		Response again = upload(student.api(), pdf, "ENHANCED");
		Response differentFile = upload(student.api(), SampleCv.pdf("OTROARCHIVO"));

		assertThat(again.status()).isEqualTo(202);
		assertThat(again.json().get("id").asString()).isEqualTo(first.json().get("id").asString());
		assertThat(again.json().get("reused").asBoolean()).isTrue();
		assertThat(again.json().get("status").asString()).isEqualTo("READY");
		assertThat(differentFile.status()).isEqualTo(409);
		assertThat(ai.calls.get()).as("no se vuelve a llamar a la IA").isEqualTo(1);
		assertThat(usageRows(student.id())).isEqualTo(1);
		assertThat(analysisRows(student.id())).isEqualTo(1);
		assertThat(student.api().get(CV + "/analyses/current").json().get("reused").asBoolean()).isTrue();
		assertThat(analysis.get("source").asString()).isEqualTo("AI");
	}

	@Test
	@DisplayName("009-26: el mismo archivo ya leído con IA en un análisis cerrado solo se lee con las reglas (30 días)")
	void t009_26_sameFileClosedAnalysisDoesNotCallTheAiAgain() {
		RegisteredStudent student = registerStudent();
		byte[] pdf = SampleCv.pdf("MARCACERRADO");
		upload(student.api(), pdf, "ENHANCED");
		JsonNode analysis = awaitDone(student.api());
		student.api().delete(CV + "/analyses/" + analysis.get("id").asString());
		assertThat(ai.calls.get()).isEqualTo(1);

		Response again = upload(student.api(), pdf, "ENHANCED");

		assertThat(again.status()).isEqualTo(202);
		assertThat(again.json().get("degraded").asBoolean()).isTrue();
		assertThat(again.json().get("degradedReason").asString()).isEqualTo("ALREADY_READ");
		JsonNode second = awaitDone(student.api());
		assertThat(second.get("source").asString()).isEqualTo("RULES");
		assertThat(second.get("degradedReason").asString()).isEqualTo("ALREADY_READ");
		assertThat(ai.calls.get()).as("la IA no se vuelve a llamar").isEqualTo(1);
		assertThat(usageRows(student.id())).as("ni se anota ni se cobra").isEqualTo(1);

		// Un archivo nuevo sí puede leerse con IA.
		student.api().delete(CV + "/analyses/" + second.get("id").asString());
		upload(student.api(), SampleCv.pdf("MARCANUEVA"), "ENHANCED");
		awaitDone(student.api());
		assertThat(ai.calls.get()).isEqualTo(2);
	}

	@Test
	@DisplayName("009-26: pasada la ventana de 30 días, el mismo archivo puede volver a leerse con IA")
	void t009_26_windowExpires() {
		RegisteredStudent student = registerStudent();
		byte[] pdf = SampleCv.pdf("MARCAVENTANA");
		upload(student.api(), pdf, "ENHANCED");
		student.api().delete(CV + "/analyses/" + awaitDone(student.api()).get("id").asString());

		clock.advance(java.time.Duration.ofDays(31));
		Response again = upload(student.api(), pdf, "ENHANCED");

		assertThat(again.json().get("degraded").asBoolean()).isFalse();
		awaitDone(student.api());
		assertThat(ai.calls.get()).isEqualTo(2);
	}

	// ------------------------------------------------------------------------------------------ 009-27

	@Test
	@DisplayName("009-27: por defecto se lee con reglas; la mejorada solo si se pide, y deja el análisis rotulado como IA")
	void t009_27_enhancedOnlyWhenAsked() {
		RegisteredStudent student = registerStudent();
		upload(student.api(), SampleCv.pdf("MARCA1"));
		JsonNode basic = awaitDone(student.api());
		assertThat(ai.calls.get()).isZero();
		student.api().delete(CV + "/analyses/" + basic.get("id").asString());

		Response enhanced = upload(student.api(), SampleCv.pdf("MARCA2"), "ENHANCED");
		JsonNode analysis = awaitDone(student.api());

		assertThat(enhanced.json().get("degraded").asBoolean()).isFalse();
		assertThat(ai.calls.get()).isEqualTo(1);
		assertThat(analysis.get("source").asString()).isEqualTo("AI");
		assertThat(analysis.get("level").asString()).isEqualTo("ENHANCED");
		assertThat(analysis.get("degraded").asBoolean()).isFalse();
		assertThat(skillSuggestion(analysis, "typescript").get("value").get("level").asInt()).isEqualTo(1);
		assertThat(academicSuggestion(analysis, "CAREER").get("value").get("value").asString())
				.isEqualTo("Ingeniería de Software");
		Map<String, Object> row = jdbc.queryForMap("SELECT adapter, adapter_version, rule_set_version FROM cv_analysis "
				+ "WHERE user_id = ? AND level = 'ENHANCED'", student.id());
		assertThat(row).containsEntry("adapter", "simulated-ai").containsEntry("adapter_version", "1.0.0")
				.containsEntry("rule_set_version", "test-instructions-1");
	}

	@Test
	@DisplayName("009-27: 'Leer mejor con IA' sobre un análisis abierto reemplaza las sugerencias por las de la IA")
	void t009_27_rereadReplacesSuggestions() {
		RegisteredStudent student = registerStudent();
		JsonNode basic = uploadSampleAndWait(student.api());
		int rules = basic.get("suggestions").size();
		assertThat(rules).isGreaterThan(3);

		Response response = enhance(student.api(), basic);
		assertThat(response.status()).isEqualTo(202);
		assertThat(response.json().get("status").asString()).isEqualTo("PENDING");
		JsonNode improved = awaitDone(student.api());

		assertThat(improved.get("source").asString()).isEqualTo("AI");
		assertThat(improved.get("suggestions")).hasSize(3);
		assertThat(improved.get("id").asString()).isEqualTo(basic.get("id").asString());
		assertThat(ai.calls.get()).isEqualTo(1);
		assertThat(usageRows(student.id(), "SUCCESS")).isEqualTo(1);
	}

	@Test
	@DisplayName("009-27: si la IA falla en una relectura se conservan las sugerencias anteriores, se avisa y no se descuenta nada")
	void t009_27_failedRereadKeepsPreviousSuggestions() {
		RegisteredStudent student = registerStudent();
		JsonNode basic = uploadSampleAndWait(student.api());
		ai.mode = CountingAiAdapter.Mode.FAIL;

		enhance(student.api(), basic);
		JsonNode after = awaitDone(student.api());

		assertThat(after.get("status").asString()).isEqualTo("READY");
		assertThat(after.get("source").asString()).isEqualTo("RULES");
		assertThat(after.get("degraded").asBoolean()).isTrue();
		assertThat(after.get("degradedReason").asString()).isEqualTo("AI_FAILED");
		assertThat(after.get("suggestions")).hasSize(basic.get("suggestions").size());
		assertThat(ai.calls.get()).as("una sola llamada, sin reintentos automáticos").isEqualTo(1);
		assertThat(usageRows(student.id(), "FAILED_PLATFORM")).isEqualTo(1);
		JsonNode usage = student.api().get(CV + "/usage").json();
		assertThat(usage.get("enhanced").get("used").asInt()).as("la falla no se descuenta").isZero();
		assertThat(usage.get("enhanced").get("available").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("009-27: si la IA falla al subir, se lee con las reglas y se puede reintentar sin que se descuente nada")
	void t009_27_failedUploadFallsBackToRulesAndCanRetry() {
		RegisteredStudent student = registerStudent();
		ai.mode = CountingAiAdapter.Mode.FAIL;

		upload(student.api(), SampleCv.pdf("MARCAFALLA"), "ENHANCED");
		JsonNode fallback = awaitDone(student.api());

		assertThat(fallback.get("status").asString()).isEqualTo("READY");
		assertThat(fallback.get("source").asString()).isEqualTo("RULES");
		assertThat(fallback.get("degradedReason").asString()).isEqualTo("AI_FAILED");
		assertThat(fallback.get("suggestions")).isNotEmpty();
		assertThat(usageRows(student.id(), "FAILED_PLATFORM")).isEqualTo(1);

		ai.mode = CountingAiAdapter.Mode.OK;
		enhance(student.api(), fallback);
		JsonNode retried = awaitDone(student.api());

		assertThat(retried.get("source").asString()).isEqualTo("AI");
		assertThat(retried.get("degraded").asBoolean()).isFalse();
		JsonNode usage = student.api().get(CV + "/usage").json();
		assertThat(usage.get("enhanced").get("used").asInt()).isEqualTo(1);
		assertThat(ai.calls.get()).isEqualTo(2);
	}

	@Test
	@DisplayName("009-29: si la IA no responde a tiempo se atiende con la lectura básica y no se descuenta")
	void t009_29_slowAiDegrades() {
		RegisteredStudent student = registerStudent();
		ai.mode = CountingAiAdapter.Mode.SLOW;

		upload(student.api(), SampleCv.pdf("MARCALENTA"), "ENHANCED");
		JsonNode analysis = awaitDone(student.api());

		assertThat(analysis.get("status").asString()).isEqualTo("READY");
		assertThat(analysis.get("source").asString()).isEqualTo("RULES");
		assertThat(analysis.get("degradedReason").asString()).isEqualTo("AI_FAILED");
		assertThat(usageRows(student.id(), "FAILED_PLATFORM")).isEqualTo(1);
		assertThat(usageRows(student.id(), "SUCCESS")).isZero();
	}

	// ------------------------------------------------------------------------------------------ 009-28

	@Test
	@DisplayName("009-28: tres lecturas con IA por mes; la cuarta da 429 ai-quota-reached con la fecha de renovación y la básica sigue")
	void t009_28_personalQuota() {
		RegisteredStudent student = registerStudent();
		upload(student.api(), SampleCv.pdf("MARCACUPO"), "ENHANCED");
		JsonNode analysis = awaitDone(student.api());
		enhance(student.api(), analysis);
		awaitDone(student.api());
		enhance(student.api(), analysis);
		awaitDone(student.api());
		assertThat(ai.calls.get()).isEqualTo(3);
		JsonNode usage = student.api().get(CV + "/usage").json();
		assertThat(usage.get("enhanced").get("used").asInt()).isEqualTo(3);
		assertThat(usage.get("enhanced").get("limit").asInt()).isEqualTo(3);
		assertThat(usage.get("enhanced").get("available").asBoolean()).isFalse();
		assertThat(usage.get("enhanced").get("reason").asString()).isEqualTo("PERSONAL_LIMIT");
		assertThat(usage.get("basic").get("available").asBoolean()).isTrue();

		Response fourth = enhance(student.api(), analysis);

		Instant renewal = LocalDate.now(LIMA).withDayOfMonth(1).plusMonths(1).atStartOfDay(LIMA).toInstant();
		assertThat(fourth.status()).isEqualTo(429);
		assertThat(fourth.json().get("code").asString()).isEqualTo("ai-quota-reached");
		assertThat(Instant.parse(fourth.json().get("resetsAt").asString())).isEqualTo(renewal);
		assertThat(Instant.parse(usage.get("enhanced").get("resetsAt").asString())).isEqualTo(renewal);
		assertThat(ai.calls.get()).as("la IA no se llamó una cuarta vez").isEqualTo(3);
		assertThat(usageRows(student.id(), "BLOCKED_PERSONAL_QUOTA")).isEqualTo(1);
		// El cupo nunca limita derechos: la lectura básica, confirmar y descartar siguen disponibles.
		assertThat(student.api().post(readsPath(analysis), Map.of("mode", "BASIC")).status()).isEqualTo(202);
		JsonNode basic = awaitDone(student.api());
		assertThat(basic.get("source").asString()).as("la lectura básica no usa el cupo ni la IA").isEqualTo("RULES");
		assertThat(ai.calls.get()).isEqualTo(3);
		assertThat(student.api().post(CV + "/analyses/" + analysis.get("id").asString() + "/confirm",
				confirmBody(List.of())).status()).isEqualTo(200);

		// El día 1 (hora de Lima) se renueva.
		clock.set(renewal.plusSeconds(5));
		assertThat(student.api().get(CV + "/usage").json().get("enhanced").get("available").asBoolean()).isTrue();
		assertThat(student.api().get(CV + "/usage").json().get("enhanced").get("used").asInt()).isZero();
	}

	@Test
	@DisplayName("009-28: pedir la lectura mejorada al subir con el cupo agotado da 429 y no deja nada guardado")
	void t009_28_uploadWithoutQuotaIs429() {
		RegisteredStudent student = registerStudent();
		for (int i = 0; i < 3; i++) {
			jdbc.update("""
					INSERT INTO ai_usage_event (occurred_at, user_id, function, level, adapter, outcome, read_id)
					VALUES (?, ?, 'CV_READ', 'ENHANCED', 'simulated-ai', 'SUCCESS', ?)""",
					java.sql.Timestamp.from(clock.instant()), student.id(), UUID.randomUUID());
		}

		Response response = upload(student.api(), SampleCv.pdf("MARCASINCUPO"), "ENHANCED");

		assertThat(response.status()).isEqualTo(429);
		assertThat(response.json().get("code").asString()).isEqualTo("ai-quota-reached");
		assertThat(analysisRows(student.id())).isZero();
		assertThat(ai.calls.get()).isZero();
		assertThat(usageRows(student.id(), "BLOCKED_PERSONAL_QUOTA")).isEqualTo(1);
		// Con la lectura básica sigue pudiendo subir.
		assertThat(upload(student.api(), SampleCv.pdf("MARCASINCUPO")).status()).isEqualTo(202);
	}

	// ------------------------------------------------------------------------------------------ 009-30

	@Test
	@DisplayName("009-30: a la IA solo salen 12 000 caracteres, cortados por líneas completas, y se avisa; una sola llamada")
	void t009_30_inputIsLimited() {
		RegisteredStudent student = registerStudent();
		List<String> lines = new ArrayList<>();
		for (int i = 0; i < 200; i++) {
			lines.add("Linea " + (1000 + i) + " de relleno inventado con palabras variadas para ocupar espacio util");
		}

		upload(student.api(), TestPdfs.of(lines), "ENHANCED");
		JsonNode analysis = awaitDone(student.api());

		assertThat(ai.calls.get()).isEqualTo(1);
		assertThat(ai.lastInputLength).isLessThanOrEqualTo(12_000).isGreaterThan(11_000);
		assertThat(ai.lastInput).endsWith("util");
		assertThat(analysis.get("inputTruncated").asBoolean()).isTrue();
		assertThat(usageRows(student.id())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT input_size FROM ai_usage_event WHERE user_id = ?", Integer.class,
				student.id())).isEqualTo(ai.lastInputLength);
	}

	@Test
	@DisplayName("009-30: a la IA llega el texto ya reducido: sin correo, teléfono, DNI, dirección ni nombre")
	void t009_30_theAiReceivesReducedText() {
		RegisteredStudent student = registerStudent();

		upload(student.api(), SampleCv.pdf("MARCAIA"), "ENHANCED");
		awaitDone(student.api());

		assertThat(ai.lastInput).contains("Java").contains("Instituto Tecsup").doesNotContain(SampleCv.EMAIL)
				.doesNotContain("987").doesNotContain(SampleCv.DNI).doesNotContain("Los Pinos")
				.doesNotContain("Valeria").doesNotContain("Quispe").doesNotContain("soltera");
	}

	// ------------------------------------------------------------------------------------------ 009-31

	@Test
	@DisplayName("009-31: ai_usage_event deja una fila por intento, sin columnas de contenido, y la suma del mes es el cupo")
	void t009_31_usageEvents() {
		RegisteredStudent student = registerStudent();
		upload(student.api(), SampleCv.pdf("MARCAMEDIDA"), "ENHANCED");
		JsonNode analysis = awaitDone(student.api());
		ai.mode = CountingAiAdapter.Mode.FAIL;
		enhance(student.api(), analysis);
		awaitDone(student.api());
		ai.mode = CountingAiAdapter.Mode.OK;
		enhance(student.api(), analysis);
		awaitDone(student.api());

		List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM ai_usage_event WHERE user_id = ? ORDER BY occurred_at",
				student.id());

		assertThat(rows).extracting(r -> r.get("outcome")).containsExactly("SUCCESS", "FAILED_PLATFORM", "SUCCESS");
		assertThat(rows).allSatisfy(row -> {
			assertThat(row.get("function")).isEqualTo("CV_READ");
			assertThat(row.get("level")).isEqualTo("ENHANCED");
			assertThat(row.get("adapter")).isEqualTo("simulated-ai");
			assertThat(row.get("model")).isEqualTo("modelo-simulado");
			assertThat(row.get("read_id")).isNotNull();
			assertThat(row.toString()).doesNotContain("MARCAMEDIDA").doesNotContain("Java");
		});
		assertThat(rows.get(0).get("input_size")).isNotEqualTo(0);
		assertThat(rows.get(0).get("output_size")).isNotEqualTo(0);
		assertThat(rows.stream().map(r -> r.get("read_id")).distinct()).hasSize(3);
		assertThat(jdbc.queryForList("""
				SELECT column_name FROM information_schema.columns WHERE table_name = 'ai_usage_event'""", String.class))
				.containsExactlyInAnyOrder("id", "occurred_at", "user_id", "function", "level", "adapter", "model",
						"outcome", "input_size", "output_size", "read_id");
		assertThat(student.api().get(CV + "/usage").json().get("enhanced").get("used").asInt())
				.isEqualTo(usageRows(student.id(), "SUCCESS")).isEqualTo(2);
	}

	// ------------------------------------------------------------------------------------------ 009-32

	@Test
	@DisplayName("009-32: dos pulsaciones seguidas de 'Leer mejor con IA' crean una sola lectura y llaman una sola vez a la IA")
	void t009_32_doubleClickIsOneRead() throws Exception {
		RegisteredStudent student = registerStudent();
		JsonNode analysis = uploadSampleAndWait(student.api());
		ai.delayMillis = 600;
		student.api().get("/api/v1/auth/csrf");

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Callable<Response>> tasks = List.of(() -> enhance(student.api(), analysis),
					() -> enhance(student.api(), analysis));
			List<Integer> statuses = new ArrayList<>();
			for (Future<Response> future : pool.invokeAll(tasks)) {
				statuses.add(future.get().status());
			}
			assertThat(statuses).containsExactlyInAnyOrder(202, 409);
		}
		finally {
			pool.shutdownNow();
		}
		awaitDone(student.api());

		assertThat(ai.calls.get()).isEqualTo(1);
		assertThat(usageRows(student.id())).isEqualTo(1);
	}

	@Test
	@DisplayName("009-32: repetir el trabajo de una lectura (por ejemplo tras un reinicio) no vuelve a llamar a la IA")
	void t009_32_repeatedJobDoesNotCallTwice() {
		RegisteredStudent student = registerStudent();
		upload(student.api(), SampleCv.pdf("MARCAREPITE"), "ENHANCED");
		JsonNode analysis = awaitDone(student.api());
		UUID id = UUID.fromString(analysis.get("id").asString());
		UUID readId = jdbc.queryForObject("SELECT current_read_id FROM cv_analysis WHERE id = ?", UUID.class, id);
		assertThat(ai.calls.get()).isEqualTo(1);

		service.run(id, readId, AiLevel.ENHANCED);
		service.run(id, readId, AiLevel.ENHANCED);

		assertThat(ai.calls.get()).isEqualTo(1);
		assertThat(usageRows(student.id())).isEqualTo(1);
		assertThat(awaitDone(student.api()).get("status").asString()).isEqualTo("READY");
	}

	@Test
	@DisplayName("009-32: si la fila de uso de la lectura ya existe, el servicio de lecturas no llama a la IA")
	void t009_32_existingUsageRowBlocksTheCall() {
		RegisteredStudent student = registerStudent();
		UUID readId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO ai_usage_event (occurred_at, user_id, function, level, adapter, outcome, read_id)
				VALUES (now(), ?, 'CV_READ', 'ENHANCED', 'simulated-ai', 'FAILED_PLATFORM', ?)""", student.id(), readId);

		ReadResult result = reads.execute(student.id(), readId, AiLevel.ENHANCED, "Java y SQL", false);

		assertThat(ai.calls.get()).isZero();
		assertThat(result.degraded()).isEqualTo("ALREADY_ATTEMPTED");
		assertThat(result.level()).isEqualTo(AiLevel.BASIC);
		assertThat(usageRows(student.id())).isEqualTo(1);
	}

	// ------------------------------------------------------------------------------------------ 009-33

	@Test
	@DisplayName("009-33: ver vacantes y cargar el perfil no consumen cupo ni escriben en ai_usage_event")
	void t009_33_browsingDoesNotConsume() {
		RegisteredStudent student = registerStudent();
		int globalBefore = count("SELECT count(*) FROM ai_usage_event");

		for (int i = 0; i < 3; i++) {
			student.api().get("/api/v1/vacancies");
			student.api().get("/api/v1/vacancies/featured");
			student.api().get("/api/v1/skills");
			student.api().get(PROFILE);
		}

		assertThat(count("SELECT count(*) FROM ai_usage_event")).isEqualTo(globalBefore);
		assertThat(ai.calls.get()).isZero();
		assertThat(Set.of(student.api().get(CV + "/usage").json().get("enhanced").get("used").asInt())).containsExactly(0);
	}

}
