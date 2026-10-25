package com.talenti.practice.port;

import java.util.List;
import java.util.function.Consumer;

/**
 * Puerto del modelo de lenguaje de DORA (spec 012, principio 8). El dominio no conoce al proveedor: hay un adaptador
 * simulado determinista y uno de OpenAI (apagado por defecto). Las tres tareas tienen entrada y salida de forma fija:
 * lo que devuelve el adaptador SIEMPRE lo valida el código antes de usarlo (012-03, 012-18, 012-20).
 *
 * <p>Todas las llamadas bloquean; quien llama impone el tiempo máximo (012-04, 012-09, 012-19) y las interrumpe si se
 * agota. Fallan con {@link AiCallException}.
 */
public interface LlmPort {

	/** Nombre estable del adaptador, por ejemplo {@code simulated} u {@code openai}. */
	String adapter();

	/** Modelo de IA; nulo si el adaptador no tiene uno. */
	String model();

	/** Versión de las instrucciones (principio 11). */
	String promptVersion();

	/**
	 * 012-02 y 012-03: una sola llamada que planifica {@code count} preguntas. A la IA solo viajan las habilidades,
	 * proyectos o requisitos de {@code basis} (RN-08): nunca nombre, contacto ni datos de otras personas.
	 */
	List<PlannedQuestion> planQuestions(PlanRequest request);

	/**
	 * 012-09: la frase puente en streaming. Cada fragmento de texto va a {@code onDelta} apenas llega. La respuesta de
	 * la persona NO viaja (no hace falta para agradecer y enlazar): solo el primer nombre, la pregunta respondida y la
	 * siguiente.
	 */
	void streamBridge(BridgeRequest request, Consumer<String> onDelta);

	/**
	 * 012-18 y 012-25: evaluación por rúbrica de una respuesta. La entrada NO tiene campo para el nombre de la persona
	 * (012-24, principio 3): solo la pregunta, la transcripción final y la rúbrica.
	 */
	RawEvaluation evaluate(EvaluationRequest request);

	/** Para qué es la sesión: cambia el tono de las instrucciones, no la forma de la salida. */
	enum Purpose {

		PRACTICE, INTERVIEW

	}

	/** Un elemento que puede citarse como base de una pregunta, por ejemplo {@code skill:java} con "Java". */
	record BasisItem(String ref, String label) {

		@Override
		public String toString() {
			return "BasisItem[ref=" + ref + "]";
		}

	}

	record PlanRequest(Purpose purpose, List<BasisItem> basis, int count) {

		public PlanRequest {
			basis = List.copyOf(basis);
		}

		@Override
		public String toString() {
			return "PlanRequest[purpose=" + purpose + ", basis=" + basis.size() + ", count=" + count + "]";
		}

	}

	/** Lo que propone la IA: aún sin validar. */
	record PlannedQuestion(String text, String basisRef) {

		@Override
		public String toString() {
			return "PlannedQuestion[...]";
		}

	}

	/**
	 * @param firstName      primer nombre para que DORA llame a la persona (D-42); nulo si no hay
	 * @param answeredQuestion la pregunta que la persona acaba de responder
	 * @param nextQuestion   la siguiente pregunta; nula si fue la última
	 * @param maxTokens      tope de la salida (respuesta corta)
	 */
	record BridgeRequest(Purpose purpose, String firstName, String answeredQuestion, String nextQuestion,
			int maxTokens) {

		@Override
		public String toString() {
			return "BridgeRequest[purpose=" + purpose + ", last=" + (nextQuestion == null) + "]";
		}

	}

	/** Un criterio de la rúbrica fija con su descripción. */
	record CriterionSpec(String code, String description) {
	}

	record EvaluationRequest(Purpose purpose, String question, String answer, List<CriterionSpec> rubric) {

		public EvaluationRequest {
			rubric = List.copyOf(rubric);
		}

		@Override
		public String toString() {
			return "EvaluationRequest[purpose=" + purpose + ", answerChars=" + (answer == null ? 0 : answer.length())
					+ "]";
		}

	}

	/** Lo que devuelve la IA para un criterio: aún sin validar (puede ser hostil, 012-20). */
	record RawCriterion(String criterion, Integer level, String comment, List<String> quotes) {

		@Override
		public String toString() {
			return "RawCriterion[...]";
		}

	}

	record RawEvaluation(List<RawCriterion> criteria) {

		public RawEvaluation {
			criteria = criteria == null ? List.of() : List.copyOf(criteria);
		}

		@Override
		public String toString() {
			return "RawEvaluation[criteria=" + criteria.size() + "]";
		}

	}

}
