package com.talenti.practice.port;

/**
 * Puerto de voz a texto (spec 012, principio 8). Recibe el audio SOLO en memoria: el adaptador no lo guarda ni lo
 * registra (D-13, 012-07). Bloquea; quien llama impone el tiempo máximo ({@code stt-timeout}) y lo interrumpe.
 */
public interface SpeechToTextPort {

	String adapter();

	String model();

	/** Transcribe el audio; texto vacío si no hay voz. Falla con {@link AiCallException}. */
	Transcription transcribe(byte[] audio, String mime);

	/**
	 * @param text    lo transcrito (vacío si no hubo voz)
	 * @param audioMs duración del audio si el servicio la informa; nula si no
	 */
	record Transcription(String text, Integer audioMs) {

		@Override
		public String toString() {
			return "Transcription[chars=" + (text == null ? 0 : text.length()) + "]";
		}

	}

}
