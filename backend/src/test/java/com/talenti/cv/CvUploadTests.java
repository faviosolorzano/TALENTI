package com.talenti.cv;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.talenti.cv.service.CvMaintenance;
import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;
import com.talenti.support.SampleCv;
import com.talenti.support.TestPdfs;

import tools.jackson.databind.JsonNode;

/** Subida y análisis del lector de CV (spec 009): 009-05 a 009-08, 009-10 y 009-11. */
class CvUploadTests extends CvIntegrationTest {

	@Autowired
	private CvMaintenance maintenance;

	// ------------------------------------------------------------------------------------------ 009-05

	@Test
	@DisplayName("009-05: un PDF válido responde 202 con el id y el estado PENDING")
	void t009_05_validPdfIsAccepted() {
		RegisteredStudent student = registerStudent();

		Response response = upload(student.api(), SampleCv.pdf("MARCA"));

		assertThat(response.status()).isEqualTo(202);
		JsonNode body = response.json();
		assertThat(UUID.fromString(body.get("id").asString())).isNotNull();
		assertThat(body.get("status").asString()).isEqualTo("PENDING");
		assertThat(body.get("reused").asBoolean()).isFalse();
		assertThat(body.get("degraded").asBoolean()).isFalse();
		assertThat(response.header("Cache-Control")).contains("no-store");
	}

	@Test
	@DisplayName("009-05: algo que no es un PDF con el tipo y la extensión de un PDF da 422 NOT_A_PDF y no queda nada guardado")
	void t009_05_fakePdfIsRejected() {
		RegisteredStudent student = registerStudent();

		Response response = upload(student.api(), "Soy texto plano disfrazado de PDF".getBytes(StandardCharsets.UTF_8));

		assertThat(response.status()).isEqualTo(422);
		assertThat(response.json().get("code").asString()).isEqualTo("cv-unreadable");
		assertThat(response.json().get("reason").asString()).isEqualTo("NOT_A_PDF");
		assertThat(analysisRows(student.id())).isZero();
	}

	@Test
	@DisplayName("009-05: un tipo que no es application/pdf da 415 cv-invalid-type")
	void t009_05_wrongTypeIs415() {
		RegisteredStudent student = registerStudent();

		Response response = student.api().postMultipart(CV + "/analyses", "cv.pdf", "text/plain",
				SampleCv.pdf("MARCA"), Map.of());

		assertThat(response.status()).isEqualTo(415);
		assertThat(response.json().get("code").asString()).isEqualTo("cv-invalid-type");
		assertThat(analysisRows(student.id())).isZero();
	}

	@Test
	@DisplayName("009-05: más de 2 MB da 413 cv-too-large, tanto si la cabecera ya lo declara (3 MB) como si pasa por poco (2,5 MB)")
	void t009_05_tooLargeIs413() {
		RegisteredStudent student = registerStudent();
		byte[] bigPdf = new byte[3 * 1024 * 1024];
		byte[] almost = new byte[2_500_000];
		System.arraycopy("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), 0, bigPdf, 0, 9);
		System.arraycopy("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII), 0, almost, 0, 9);

		Response close = upload(student.api(), almost);
		// Con 3 MB la cabecera ya lo declara y el servidor responde sin leer el cuerpo: el cliente puede ver el
		// corte de la conexión en vez de la respuesta; ambas cosas son un rechazo.
		try {
			Response big = upload(student.api(), bigPdf);
			assertThat(big.status()).isEqualTo(413);
			assertThat(big.json().get("code").asString()).isEqualTo("cv-too-large");
		}
		catch (IllegalStateException connectionCut) {
			assertThat(connectionCut.getCause()).isInstanceOf(java.io.IOException.class);
		}
		assertThat(close.status()).isEqualTo(413);
		assertThat(close.json().get("code").asString()).isEqualTo("cv-too-large");
		assertThat(analysisRows(student.id())).isZero();
	}

	@Test
	@DisplayName("009-05: cifrado, corrupto, escaneado y de 11 páginas dan 422 cv-unreadable con su motivo")
	void t009_05_unreadableReasons() {
		RegisteredStudent student = registerStudent();
		Map<String, byte[]> cases = new java.util.LinkedHashMap<>();
		cases.put("ENCRYPTED", TestPdfs.encrypted(SampleCv.lines("M")));
		cases.put("CORRUPT", TestPdfs.corrupt());
		cases.put("NO_TEXT", TestPdfs.scanned());
		cases.put("TOO_MANY_PAGES", TestPdfs.withPages(11));

		cases.forEach((reason, pdf) -> {
			Response response = upload(student.api(), pdf);

			assertThat(response.status()).as(reason).isEqualTo(422);
			assertThat(response.json().get("code").asString()).isEqualTo("cv-unreadable");
			assertThat(response.json().get("reason").asString()).isEqualTo(reason);
			assertThat(response.body()).as("el error no repite el contenido").doesNotContain("Valeria");
		});
		assertThat(analysisRows(student.id())).as("nada queda guardado al fallar").isZero();
	}

	@Test
	@DisplayName("009-05: sin archivo o con un modo desconocido es un 422 de validación")
	void t009_05_missingFileAndBadMode() {
		RegisteredStudent student = registerStudent();

		Response noFile = student.api().postMultipart(CV + "/analyses", "x.pdf", "application/pdf", null, Map.of());
		Response badMode = upload(student.api(), SampleCv.pdf("MARCA"), "SUPER");

		assertThat(noFile.status()).isEqualTo(422);
		assertThat(noFile.json().get("errors").get(0).get("field").asString()).isEqualTo("file");
		assertThat(badMode.status()).isEqualTo(422);
		assertThat(badMode.json().get("errors").get(0).get("field").asString()).isEqualTo("mode");
		assertThat(analysisRows(student.id())).isZero();
	}

	// ------------------------------------------------------------------------------------------ 009-06 y 009-10

	@Test
	@DisplayName("009-06: el PDF no se guarda en ninguna columna ni se devuelve; el nombre del archivo se descarta")
	void t009_06_theFileIsNeverStoredOrReturned() {
		RegisteredStudent student = registerStudent();
		Response uploaded = upload(student.api(), SampleCv.pdf("MARCA"));
		JsonNode analysis = awaitDone(student.api());

		Integer binaryColumns = jdbc.queryForObject("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_name IN ('cv_analysis', 'cv_suggestion') AND data_type IN ('bytea', 'oid')""",
				Integer.class);
		assertThat(binaryColumns).isZero();
		assertThat(uploaded.body() + analysis).doesNotContain("mi-cv-secreto").doesNotContain("%PDF");
		assertThat(jdbc.queryForObject("SELECT file_size FROM cv_analysis WHERE id = ?::uuid", Integer.class,
				analysis.get("id").asString())).isPositive();
	}

	@Test
	@DisplayName("009-09: el texto que se conserva está ya reducido: sin correo, teléfono, DNI, dirección ni nombre")
	void t009_09_storedTextIsReduced() {
		RegisteredStudent student = registerStudent();
		uploadSampleAndWait(student.api());

		String stored = jdbc.queryForObject("SELECT reduced_text FROM cv_analysis WHERE user_id = ?", String.class,
				student.id());

		assertThat(stored).contains("Java").contains("Instituto Tecsup").contains("https://github.com/valeria-demo")
				.doesNotContain(SampleCv.EMAIL).doesNotContain("987").doesNotContain(SampleCv.DNI)
				.doesNotContain("Los Pinos").doesNotContain("soltera").doesNotContain("12/05/2002")
				.doesNotContain("Valeria").doesNotContain("Quispe");
	}

	@Test
	@DisplayName("009-10: al descartar, el texto reducido y las sugerencias se borran pero la fila queda para el límite de 24 horas")
	void t009_10_discardDeletesContent() {
		RegisteredStudent student = registerStudent();
		JsonNode analysis = uploadSampleAndWait(student.api());

		Response discarded = student.api().delete(CV + "/analyses/" + analysis.get("id").asString());

		assertThat(discarded.status()).isEqualTo(204);
		assertThat(jdbc.queryForObject("SELECT reduced_text FROM cv_analysis WHERE user_id = ?", String.class,
				student.id())).isNull();
		assertThat(count("SELECT count(*) FROM cv_suggestion s JOIN cv_analysis a ON a.id = s.analysis_id "
				+ "WHERE a.user_id = ?", student.id())).isZero();
		assertThat(analysisRows(student.id())).isEqualTo(1);
		assertThat(student.api().get(CV + "/analyses/current").status()).isEqualTo(204);
	}

	@Test
	@DisplayName("009-10: a los 7 días la tarea programada expira lo vencido, borra su contenido y audita CV_EXPIRED")
	void t009_10_expiryAfterSevenDays() {
		RegisteredStudent student = registerStudent();
		JsonNode analysis = uploadSampleAndWait(student.api());
		UUID id = UUID.fromString(analysis.get("id").asString());

		maintenance.expireOverdue();
		assertThat(jdbc.queryForObject("SELECT status FROM cv_analysis WHERE id = ?", String.class, id))
				.as("todavía no vence").isEqualTo("READY");

		clock.advance(Duration.ofDays(7).plusMinutes(1));
		assertThat(maintenance.expireOverdue()).isGreaterThanOrEqualTo(1);

		assertThat(jdbc.queryForObject("SELECT status FROM cv_analysis WHERE id = ?", String.class, id))
				.isEqualTo("EXPIRED");
		assertThat(jdbc.queryForObject("SELECT reduced_text FROM cv_analysis WHERE id = ?", String.class, id)).isNull();
		assertThat(count("SELECT count(*) FROM cv_suggestion WHERE analysis_id = ?", id)).isZero();
		assertThat(count("SELECT count(*) FROM audit_event WHERE event_type = 'CV_EXPIRED' AND subject_id = ?", id))
				.isEqualTo(1);
		// Ya no hay análisis vigente: el cierre libera al estudiante para subir otro.
		assertThat(student.api().get(CV + "/analyses/current").status()).isEqualTo(204);
	}

	// ------------------------------------------------------------------------------------------ 009-07

	@Test
	@DisplayName("009-07: con un análisis abierto, subir otro CV distinto da 409 analysis-in-progress")
	void t009_07_oneOpenAnalysis() {
		RegisteredStudent student = registerStudent();
		uploadSampleAndWait(student.api());

		Response second = upload(student.api(), TestPdfs.of(SampleCv.lines("OTRO")));

		assertThat(second.status()).isEqualTo(409);
		assertThat(second.json().get("code").asString()).isEqualTo("analysis-in-progress");
		assertThat(analysisRows(student.id())).isEqualTo(1);
	}

	@Test
	@DisplayName("009-07: dos subidas a la vez de la misma persona crean un solo análisis abierto")
	void t009_07_concurrentUploadsCreateOne() throws Exception {
		RegisteredStudent student = registerStudent();
		student.api().get("/api/v1/auth/csrf");
		byte[] first = SampleCv.pdf("UNO");
		byte[] second = SampleCv.pdf("DOS");

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			List<Callable<Response>> tasks = List.of(() -> upload(student.api(), first), () -> upload(student.api(), second));
			List<Integer> statuses = new ArrayList<>();
			for (Future<Response> future : pool.invokeAll(tasks)) {
				statuses.add(future.get().status());
			}
			assertThat(statuses).containsExactlyInAnyOrder(202, 409);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(count("SELECT count(*) FROM cv_analysis WHERE user_id = ? AND status IN ('PENDING','ANALYZING','READY')",
				student.id())).isEqualTo(1);
	}

	@Test
	@DisplayName("009-07: el sexto análisis en 24 horas da 429 cv-limit-reached con Retry-After; pasadas 24 horas vuelve a dejar")
	void t009_07_dailyLimit() {
		RegisteredStudent student = registerStudent();
		for (int i = 0; i < 5; i++) {
			assertThat(upload(student.api(), TestPdfs.of(SampleCv.lines("N" + i))).status()).isEqualTo(202);
			JsonNode current = awaitDone(student.api());
			assertThat(student.api().delete(CV + "/analyses/" + current.get("id").asString()).status()).isEqualTo(204);
		}

		Response sixth = upload(student.api(), TestPdfs.of(SampleCv.lines("SEXTO")));

		assertThat(sixth.status()).isEqualTo(429);
		assertThat(sixth.json().get("code").asString()).isEqualTo("cv-limit-reached");
		assertThat(Long.parseLong(sixth.header("Retry-After"))).isPositive();

		clock.advance(Duration.ofHours(25));
		assertThat(upload(student.api(), TestPdfs.of(SampleCv.lines("SEPTIMO"))).status()).isEqualTo(202);
	}

	@Test
	@DisplayName("009-07: más de 10 solicitudes por minuto desde un mismo origen dan 429 cv-limit-reached")
	void t009_07_originLimit() {
		RegisteredStudent student = registerStudent();
		Response last = null;
		for (int i = 0; i < 11; i++) {
			last = student.api().postMultipart(CV + "/analyses", "x.txt", "text/plain", new byte[] { 1 }, Map.of());
		}

		assertThat(last.status()).isEqualTo(429);
		assertThat(last.json().get("code").asString()).isEqualTo("cv-limit-reached");
		assertThat(last.header("Retry-After")).isNotNull();
	}

	// ------------------------------------------------------------------------------------------ 009-08

	@Test
	@DisplayName("009-08: el análisis pasa a READY y la consulta devuelve 204 si no hay ninguno")
	void t009_08_statesAndCurrent() {
		RegisteredStudent student = registerStudent();
		assertThat(student.api().get(CV + "/analyses/current").status()).isEqualTo(204);

		assertThat(upload(student.api(), SampleCv.pdf("MARCA")).status()).isEqualTo(202);
		JsonNode analysis = awaitDone(student.api());

		assertThat(analysis.get("status").asString()).isEqualTo("READY");
		assertThat(analysis.get("source").asString()).isEqualTo("RULES");
		assertThat(analysis.get("level").asString()).isEqualTo("BASIC");
		assertThat(analysis.get("failureReason").isNull()).isTrue();
		assertThat(analysis.get("createdAt").asString()).isNotBlank();
		assertThat(analysis.get("expiresAt").asString()).isNotBlank();
		assertThat(analysis.get("alreadyInProfile").asInt()).isZero();
		assertThat(analysis.get("suggestions")).isNotEmpty();
	}

	@Test
	@DisplayName("009-08: un CV sin nada que sugerir queda READY con cero sugerencias, que no es un error")
	void t009_08_emptyResultIsNotAnError() {
		RegisteredStudent student = registerStudent();
		byte[] pdf = TestPdfs.of(TestPdfs.lines(15, "Texto sin ningun dato reconocible para el lector"));

		assertThat(upload(student.api(), pdf).status()).isEqualTo(202);
		JsonNode analysis = awaitDone(student.api());

		assertThat(analysis.get("status").asString()).isEqualTo("READY");
		assertThat(analysis.get("suggestions")).isEmpty();
	}

	@Test
	@DisplayName("009-08: un análisis que quedó a medias por un reinicio pasa a FAILED al arrancar y se puede reintentar")
	void t009_08_orphanBecomesFailed() {
		RegisteredStudent student = registerStudent();
		JsonNode ready = uploadSampleAndWait(student.api());
		UUID id = UUID.fromString(ready.get("id").asString());
		jdbc.update("UPDATE cv_analysis SET status = 'ANALYZING' WHERE id = ?", id);

		maintenance.failOrphans();

		JsonNode failed = student.api().get(CV + "/analyses/current").json();
		assertThat(failed.get("status").asString()).isEqualTo("FAILED");
		assertThat(failed.get("failureReason").asString()).isEqualTo("RESTART");
		assertThat(failed.get("suggestions")).isEmpty();

		Response retry = student.api().post(CV + "/analyses/" + id + "/reads", Map.of("mode", "BASIC"));
		assertThat(retry.status()).isEqualTo(202);
		assertThat(awaitDone(student.api()).get("status").asString()).isEqualTo("READY");
	}

	@Test
	@DisplayName("009-08: 'Volver a leer' sobre un análisis abierto relee con las reglas; mientras se lee, otra pulsación da 409")
	void t009_08_readAgain() {
		RegisteredStudent student = registerStudent();
		JsonNode ready = uploadSampleAndWait(student.api());
		String id = ready.get("id").asString();

		Response again = student.api().post(CV + "/analyses/" + id + "/reads", Map.of("mode", "BASIC"));

		assertThat(again.status()).isEqualTo(202);
		assertThat(again.json().get("status").asString()).isEqualTo("PENDING");
		JsonNode done = awaitDone(student.api());
		assertThat(done.get("status").asString()).isEqualTo("READY");
		assertThat(done.get("suggestions").size()).isEqualTo(ready.get("suggestions").size());
		// Un análisis cerrado ya no se puede releer.
		student.api().delete(CV + "/analyses/" + id);
		assertThat(student.api().post(CV + "/analyses/" + id + "/reads", Map.of("mode", "BASIC")).status())
				.isEqualTo(404);
	}

	// ------------------------------------------------------------------------------------------ 009-11

	@Test
	@DisplayName("009-11: cada análisis guarda el adaptador, su versión y la versión de las reglas")
	void t009_11_storesAdapterInfo() {
		RegisteredStudent student = registerStudent();
		uploadSampleAndWait(student.api());

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT adapter, adapter_version, rule_set_version, level FROM cv_analysis WHERE user_id = ?",
				student.id());

		assertThat(row.get("adapter")).isEqualTo("rules");
		assertThat(row.get("adapter_version")).isNotNull();
		assertThat(row.get("rule_set_version")).isNotNull();
		assertThat(row.get("level")).isEqualTo("BASIC");
	}

	@Test
	@DisplayName("009-27: sin la IA encendida, pedir la lectura mejorada se atiende con la básica y lo dice (DISABLED)")
	void t009_27_enhancedWithAiOffDegrades() {
		RegisteredStudent student = registerStudent();

		Response response = upload(student.api(), SampleCv.pdf("MARCA"), "ENHANCED");

		assertThat(response.status()).isEqualTo(202);
		assertThat(response.json().get("degraded").asBoolean()).isTrue();
		assertThat(response.json().get("degradedReason").asString()).isEqualTo("DISABLED");
		JsonNode analysis = awaitDone(student.api());
		assertThat(analysis.get("source").asString()).isEqualTo("RULES");
		assertThat(analysis.get("degraded").asBoolean()).isTrue();
		assertThat(usageRows(student.id())).as("sin IA no hay medición").isZero();
		JsonNode usage = student.api().get(CV + "/usage").json();
		assertThat(usage.get("basic").get("available").asBoolean()).isTrue();
		assertThat(usage.get("enhanced").get("available").asBoolean()).isFalse();
		assertThat(usage.get("enhanced").get("reason").asString()).isEqualTo("DISABLED");
	}

}
