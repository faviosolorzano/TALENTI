package com.talenti.practice.port;

import java.util.Optional;

/**
 * Puerto de la conversación con DORA (012-31): lo único que conoce el servicio para hablar. Un turno es: entrada (audio
 * o texto) → {@code transcript} → frase puente en texto y voz ({@code replyText}, {@code replyAudio}) → {@code done}.
 * Hoy lo implementa un adaptador POR TURNOS que usa, detrás, {@link LlmPort}, {@link SpeechToTextPort} y
 * {@link TextToSpeechPort}. Pasar a tiempo real será otro adaptador de este puerto, más su canal; el dominio y las
 * tablas no cambian.
 *
 * <p>El adaptador impone los tiempos máximos de cada paso y nunca deja un turno sin {@code onDone} u {@code onError}
 * (012-17). Nunca guarda ni registra audio, texto ni el nombre.
 */
public interface ConversationPort {

	Info info();

	/** Ejecuta un turno completo; bloquea hasta terminar y avisa cada paso en orden a {@code listener}. */
	void runTurn(TurnInput input, TurnListener listener);

	/**
	 * Sintetiza un texto fijo de DORA (saludo, pregunta, cierre) con el tiempo máximo de la voz. Vacío si la voz falla o
	 * tarda (el turno sigue con texto, 012-11).
	 */
	Optional<SpokenAudio> speak(String text, CallLog log);

	/** Quién conversa (principio 11): se guarda con cada sesión. */
	record Info(String adapter, String llmModel, String sttModel, String ttsModel, String ttsVoice,
			String promptVersion) {
	}

	/** Audio del turno, solo en memoria. {@code toString} no muestra el contenido. */
	record InputAudio(byte[] data, String mime, Integer durationMs) {

		@Override
		public String toString() {
			return "InputAudio[mime=" + mime + ", bytes=" + (data == null ? 0 : data.length) + "]";
		}

	}

	/**
	 * @param audio          audio del turno; nulo si es por texto
	 * @param text           texto del turno; nulo si es por audio
	 * @param bridge         datos para la frase puente (sin la respuesta de la persona)
	 * @param templateBridge frase de plantilla si la IA no responde a tiempo ({@code source = TEMPLATE})
	 * @param speak          si DORA habla en esta sesión
	 * @param aiAllowed      falso si el tope de gasto se agotó: plantilla, sin voz (012-30)
	 * @param receivedNanos  cuándo llegó el pedido ({@link System#nanoTime()}); el plazo de la transcripción cuenta
	 *                       desde ahí. 0 = desde que empieza el turno
	 */
	record TurnInput(InputAudio audio, String text, LlmPort.BridgeRequest bridge, String templateBridge, boolean speak,
			boolean aiAllowed, CallLog log, long receivedNanos) {

		@Override
		public String toString() {
			return "TurnInput[audio=" + (audio != null) + ", speak=" + speak + ", aiAllowed=" + aiAllowed + "]";
		}

	}

	enum ReplySource {

		AI, TEMPLATE

	}

	/**
	 * Cómo terminó la frase puente.
	 *
	 * @param llmDoneNanos instante ({@link System#nanoTime()}) en que el modelo terminó de responder; 0 si no respondió
	 */
	record BridgeOutcome(ReplySource source, long llmDoneNanos) {
	}

	/** Lo que va pasando en el turno, en orden. */
	interface TurnListener {

		/**
		 * La transcripción (o el texto) ya está: quien escucha la guarda y la emite. Devuelve falso para cortar el
		 * turno (por ejemplo, si no se pudo guardar).
		 *
		 * @param sttMs   cuánto tardó la transcripción; nulo si el turno es por texto
		 * @param audioMs duración del audio si se conoce
		 */
		boolean onTranscript(String text, Long sttMs, Integer audioMs);

		void onReplyText(int seq, String text, ReplySource source);

		void onReplyAudio(int seq, SpokenAudio audio);

		/** Una parte se degradó (por ejemplo {@code TTS_UNAVAILABLE}); el turno sigue. */
		void onDegraded(String reason);

		/** El turno termina con error (por ejemplo {@code no-speech} o {@code stt-failed}). */
		void onError(String code, boolean retryable);

		void onDone(BridgeOutcome outcome);

	}

	/** Qué servicio se llamó. */
	enum CallKind {

		DIALOG, STT, TTS

	}

	/**
	 * Anota cada llamada a la IA o a la voz para medir consumo y costo (012-30): solo tamaños, nunca contenido.
	 *
	 * @param inputSize  caracteres enviados; en voz a texto, milisegundos de audio
	 * @param outputSize caracteres recibidos; en texto a voz, milisegundos de audio
	 */
	@FunctionalInterface
	interface CallLog {

		CallLog NONE = (kind, model, inputSize, outputSize, success) -> {
		};

		void call(CallKind kind, String model, int inputSize, int outputSize, boolean success);

	}

}
