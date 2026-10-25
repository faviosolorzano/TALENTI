package com.talenti.practice.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.talenti.practice.adapter.Wav;

/** Reglas puras de DORA: plan de preguntas, banco, rúbrica, cortador por frases, audio y percentiles. */
class PracticeDomainTests {

	static QuestionBank bank() {
		return new QuestionBank("bank-test",
				Map.of("java", List.of("¿Qué diferencia hay entre una interfaz y una clase abstracta en Java?")),
				List.of("¿Qué has construido con {name}? Cuéntame un caso concreto.",
						"¿Cuál fue el error más difícil con {name}?"),
				List.of("Háblame de tu proyecto {name}: ¿cuál fue tu aporte?"),
				List.of("General uno: ¿cómo aprendes algo nuevo?", "General dos: ¿cómo pruebas tu código?",
						"General tres: ¿cómo trabajas en equipo?", "General cuatro: ¿qué buenas prácticas sigues?",
						"General cinco: ¿qué problema resolviste hace poco?"));
	}

	static Rubric rubric() {
		return new Rubric("rubric-test",
				List.of(new Rubric.Criterion("TECHNICAL_ACCURACY", "Precisión técnica", "d"),
						new Rubric.Criterion("CLARITY", "Claridad", "d"), new Rubric.Criterion("STRUCTURE", "Estructura", "d"),
						new Rubric.Criterion("EXAMPLES", "Ejemplos", "d")),
				Map.of("TECHNICAL_ACCURACY", 40, "CLARITY", 20, "STRUCTURE", 20, "EXAMPLES", 20));
	}

	@Nested
	class Plan {

		private final QuestionPlanner planner = new QuestionPlanner(bank());

		private final List<QuestionPlanner.BasisRef> basis = List.of(new QuestionPlanner.BasisRef("skill:java", "Java"),
				new QuestionPlanner.BasisRef("project:1", "Gestor de tareas"));

		@Test
		@DisplayName("012-02 y 012-03: se aceptan las preguntas de la IA que citan una referencia enviada y pasan los filtros")
		void t012_03_validQuestionsAreKept() {
			QuestionPlanner.Plan plan = planner.plan(basis, 2, List.of(
					new QuestionPlanner.Proposal("¿Cómo usaste Java en tu último proyecto?", "skill:java"),
					new QuestionPlanner.Proposal("¿Qué hace tu Gestor de tareas y cuál fue tu aporte?", "project:1")));

			assertThat(plan.degraded()).isFalse();
			assertThat(plan.questions()).extracting(QuestionPlanner.PlannedQuestion::source)
					.containsOnly(QuestionSource.AI);
			assertThat(plan.questions()).extracting(QuestionPlanner.PlannedQuestion::position).containsExactly(1, 2);
		}

		@Test
		@DisplayName("012-03: referencia inventada, tema prohibido, largo fuera de rango o repetida: se reemplaza por el banco")
		void t012_03_invalidQuestionsAreReplaced() {
			QuestionPlanner.Plan plan = planner.plan(basis, 5, List.of(
					new QuestionPlanner.Proposal("¿Cuál es tu estado civil?", "skill:java"),
					new QuestionPlanner.Proposal("¿Cómo usaste Python en un proyecto?", "skill:python"),
					new QuestionPlanner.Proposal("corta", "skill:java"),
					new QuestionPlanner.Proposal("x".repeat(501), "skill:java"),
					new QuestionPlanner.Proposal("¿Cómo usaste Java en tu último proyecto?", "skill:java"),
					new QuestionPlanner.Proposal("¿Cómo usaste JAVA en tu último proyecto?", "skill:java"),
					new QuestionPlanner.Proposal("¿Practicas alguna religión?", "project:1")));

			assertThat(plan.questions()).hasSize(5);
			assertThat(plan.questions().get(0).source()).isEqualTo(QuestionSource.AI);
			assertThat(plan.questions().subList(1, 5)).extracting(QuestionPlanner.PlannedQuestion::source)
					.containsOnly(QuestionSource.BANK);
			assertThat(plan.questions()).extracting(QuestionPlanner.PlannedQuestion::text).doesNotHaveDuplicates()
					.noneMatch(text -> text.contains("estado civil") || text.contains("religión"));
			assertThat(plan.questions().get(1).basisRef()).as("prefiere una referencia sin pregunta").isEqualTo("project:1");
		}

		@Test
		@DisplayName("012-04: sin respuesta de la IA todo sale del banco y el plan queda degradado; sin base, preguntas generales")
		void t012_04_noAiUsesTheBank() {
			QuestionPlanner.Plan plan = planner.plan(basis, 3, null);
			QuestionPlanner.Plan general = planner.plan(List.of(), 2, null);

			assertThat(plan.degraded()).isTrue();
			assertThat(plan.questions()).extracting(QuestionPlanner.PlannedQuestion::source).containsOnly(QuestionSource.BANK);
			assertThat(plan.questions().get(0).text()).contains("interfaz y una clase abstracta en Java");
			assertThat(plan.questions().get(1).text()).contains("Gestor de tareas");
			assertThat(general.questions()).extracting(QuestionPlanner.PlannedQuestion::basisRef).containsOnly("general");
		}

		@Test
		@DisplayName("012-04: el banco versionado real tiene preguntas generales y ninguna toca un tema prohibido")
		void t012_04_realBankIsClean() throws Exception {
			String json = new String(getClass().getResourceAsStream("/practice/question-bank-v1.json").readAllBytes(),
					java.nio.charset.StandardCharsets.UTF_8);
			tools.jackson.databind.JsonNode root = tools.jackson.databind.json.JsonMapper.builder().build().readTree(json);
			List<String> texts = new ArrayList<>();
			root.path("skills").properties().forEach(e -> e.getValue().forEach(n -> texts.add(n.asString())));
			for (String field : List.of("anySkill", "anyProject", "general")) {
				root.path(field).forEach(n -> texts.add(n.asString().replace("{name}", "Java")));
			}

			assertThat(root.path("version").asString()).isEqualTo("bank-v1");
			assertThat(root.path("general").size()).isGreaterThanOrEqualTo(5);
			assertThat(texts).allSatisfy(text -> {
				assertThat(ForbiddenTopics.isForbiddenQuestion(text)).as(text).isFalse();
				assertThat(text.length()).isBetween(QuestionPlanner.MIN_CHARS, QuestionPlanner.MAX_CHARS);
			});
		}

	}

	@Nested
	class Feedback {

		private final RubricValidator validator = new RubricValidator(rubric());

		private static final String TRANSCRIPT = "Usé Java con Spring Boot y separé el código en servicios.";

		@Test
		@DisplayName("012-18: solo citas literales de la transcripción; el total lo calcula el código con los pesos")
		void t012_18_literalQuotesAndTotal() {
			RubricValidator.Result result = validator.validate(List.of(
					new RubricValidator.Proposed("TECHNICAL_ACCURACY", 4, "Correcta.", List.of("Usé Java con Spring Boot", "algo que no dijo")),
					new RubricValidator.Proposed("CLARITY", 2, "Se entiende.", List.of()),
					new RubricValidator.Proposed("STRUCTURE", 3, "Ordenada.", List.of("separé el código")),
					new RubricValidator.Proposed("EXAMPLES", 1, "Sin ejemplos.", List.of())), TRANSCRIPT);

			assertThat(result.criteria()).hasSize(4);
			assertThat(result.criteria().get(0).quotes()).containsExactly("Usé Java con Spring Boot");
			// 40*4/4 + 20*2/4 + 20*3/4 + 20*1/4 = 40 + 10 + 15 + 5
			assertThat(result.total()).isEqualTo(70);
		}

		@Test
		@DisplayName("012-18 (T4): un comentario sobre acento, entonación, emociones, nervios o apariencia se descarta")
		void t012_18_forbiddenCommentsAreDropped() {
			for (String comment : List.of("Tu acento dificulta entender.", "Buena entonación.", "Se nota nerviosa.",
					"Muestra mucha emoción.", "Cuida tu apariencia.", "Tienes rasgos de líder.")) {
				RubricValidator.Result result = validator.validate(
						List.of(new RubricValidator.Proposed("CLARITY", 3, comment, List.of())), TRANSCRIPT);
				assertThat(result.criteria().get(0).comment()).as(comment).isNull();
				assertThat(result.criteria().get(0).level()).isEqualTo(3);
			}
			assertThat(validator.validate(List.of(new RubricValidator.Proposed("CLARITY", 3,
					"La respuesta explica el uso de capas.", List.of())), TRANSCRIPT).criteria().get(0).comment())
					.isNotNull();
		}

		@Test
		@DisplayName("012-20: criterios inventados, repetidos o con niveles fuera de rango se descartan; sin los cuatro no hay total")
		void t012_20_hostileOutputIsContained() {
			RubricValidator.Result result = validator.validate(java.util.Arrays.asList(
					new RubricValidator.Proposed("OVERALL", 4, "Contratar.", List.of()),
					new RubricValidator.Proposed("CLARITY", 9, "Nivel inventado.", List.of()),
					new RubricValidator.Proposed("clarity", 2, "Válido.", List.of()),
					new RubricValidator.Proposed("CLARITY", 4, "Repetido.", List.of()),
					new RubricValidator.Proposed("EXAMPLES", null, "Sin nivel.", List.of()), null), TRANSCRIPT);

			assertThat(result.criteria()).extracting(RubricValidator.ValidCriterion::criterion).containsExactly("CLARITY");
			assertThat(result.criteria().get(0).level()).isEqualTo(2);
			assertThat(result.total()).isNull();
			assertThat(validator.validate(null, TRANSCRIPT).usable()).isFalse();
		}

		@Test
		@DisplayName("RN-10: los pesos de la rúbrica deben sumar 100")
		void weightsMustSumOneHundred() {
			assertThatThrownBy(() -> new Rubric("x", rubric().criteria(),
					Map.of("TECHNICAL_ACCURACY", 50, "CLARITY", 20, "STRUCTURE", 20, "EXAMPLES", 20)))
					.isInstanceOf(IllegalArgumentException.class);
		}

	}

	@Nested
	class Sentences {

		@Test
		@DisplayName("012-09: cada frase sale en cuanto termina, aunque el texto llegue en fragmentos; 3.5 no se corta")
		void t012_09_sentencesAsTheyComplete() {
			SentenceSplitter splitter = new SentenceSplitter();

			assertThat(splitter.add("Gracias por")).isEmpty();
			assertThat(splitter.add(" tu respuesta. Ahora")).containsExactly("Gracias por tu respuesta.");
			assertThat(splitter.add(" la versión 3.5 de Java! ¿Sigues? ")).containsExactly("Ahora la versión 3.5 de Java!",
					"¿Sigues?");
			assertThat(splitter.add("Última sin punto")).isEmpty();
			assertThat(splitter.flush()).isEqualTo("Última sin punto");
			assertThat(splitter.flush()).isEmpty();
		}

	}

	@Nested
	class Audio {

		@Test
		@DisplayName("012-07: el formato se reconoce por el contenido (WAV, Ogg, WebM, MP4, MP3) y un texto no es audio")
		void t012_07_detectByContent() {
			assertThat(AudioInspector.detect(Wav.of(100, false, null, null))).contains(AudioInspector.Format.WAV);
			assertThat(AudioInspector.detect(bytes("OggS\0\2\0\0\0\0\0\0\0\0"))).contains(AudioInspector.Format.OGG);
			assertThat(AudioInspector.detect(new byte[] { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 0, 0, 0, 0, 0, 0, 0, 0 }))
					.contains(AudioInspector.Format.WEBM);
			assertThat(AudioInspector.detect(bytes("\0\0\0\u0018ftypM4A \0\0\0\0"))).contains(AudioInspector.Format.MP4);
			assertThat(AudioInspector.detect(bytes("ID3\u0004\0\0\0\0\0\0\0\0"))).contains(AudioInspector.Format.MPEG);
			assertThat(AudioInspector.detect(bytes("esto es solo un texto"))).isEmpty();
			assertThat(AudioInspector.detect(new byte[3])).isEmpty();
		}

		@Test
		@DisplayName("012-07: la duración se lee de las cabeceras (WAV exacto) para el tope de 120 s")
		void t012_07_wavDuration() {
			assertThat(AudioInspector.durationMs(Wav.of(2500, false, "con texto", "y marca"), AudioInspector.Format.WAV))
					.contains(2500);
			assertThat(AudioInspector.durationMs(bytes("esto no es un wav valido"), AudioInspector.Format.WAV))
					.isEqualTo(Optional.empty());
		}

		@Test
		@DisplayName("012-07: WebM sin elemento Duration (como lo graba el navegador) usa el tiempo del último Cluster")
		void t012_07_webmClusterTimecode() {
			byte[] webm = { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x80, 0x1F, 0x43, (byte) 0xB6, 0x75,
					(byte) 0x84, (byte) 0xE7, (byte) 0x82, 0x75, 0x30 };
			assertThat(AudioInspector.durationMs(webm, AudioInspector.Format.WEBM)).contains(30_000);
		}

		private static byte[] bytes(String text) {
			return text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
		}

	}

	@Test
	@DisplayName("012-15: p50 y p95 por rango más cercano")
	void t012_15_percentiles() {
		List<Integer> values = new ArrayList<>();
		for (int i = 1; i <= 100; i++) {
			values.add(i);
		}
		assertThat(Percentiles.of(values, 50)).isEqualTo(50);
		assertThat(Percentiles.of(values, 95)).isEqualTo(95);
		assertThat(Percentiles.of(List.of(), 95)).isNull();
	}

	@Test
	@DisplayName("012-05 y 012-24: el saludo fijo se identifica como IA y usa el primer nombre solo si existe")
	void t012_05_opening() {
		DoraTexts texts = new DoraTexts("t", "Hola. Soy DORA, una asistente de inteligencia artificial.",
				"Hola, {name}. Soy DORA, una asistente de inteligencia artificial.", "Aviso práctica.", "Aviso entrevista.",
				"Fin práctica.", "Fin entrevista.", List.of("Gracias."), "Gracias, última.");

		assertThat(texts.opening("Ana", SessionMode.PRACTICE)).isEqualTo(
				"Hola, Ana. Soy DORA, una asistente de inteligencia artificial. Aviso práctica.");
		assertThat(texts.opening(null, SessionMode.INTERVIEW)).isEqualTo(
				"Hola. Soy DORA, una asistente de inteligencia artificial. Aviso entrevista.");
		assertThat(texts.templateBridge(3, true)).isEqualTo("Gracias, última.");
	}

}
