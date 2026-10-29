package com.talenti.practice.port;

/**
 * Puerto de la conversación en TIEMPO REAL (spec 012, enmienda 2): el servidor recibe la oferta SDP del navegador, abre
 * la llamada con el proveedor (con la configuración de la sesión y la clave, que NUNCA sale del servidor) y devuelve
 * la respuesta SDP. Después, el audio va directo del navegador al proveedor. El dominio no conoce al proveedor
 * (principio 8): hay un adaptador simulado (todas las pruebas) y uno de OpenAI, apagado por defecto.
 *
 * <p>Ningún método registra ni guarda el SDP (lleva direcciones IP), las instrucciones ni la clave (012-43, 012-57).
 */
public interface RealtimeCallPort {

	/** Quién conversa (principio 11): se guarda con la sesión. */
	record Info(String adapter, String model, String voice, String transcriptionModel) {
	}

	/** Nombres del canal de datos y de los eventos que el navegador debe escuchar (configuración, a confirmar). */
	record EventNames(String channel, String personTranscript, String doraTranscript, String doraTranscriptDelta,
			String speechStarted, String speechStopped, String outputAudioStarted, String outputAudioStopped,
			String functionCall, String responseDone, String itemCreate, String responseCreate) {
	}

	/**
	 * @param sdp          oferta SDP del navegador, sin cambios
	 * @param instructions instrucciones de la entrevistadora (incluyen el primer nombre y el plan)
	 */
	record CallRequest(String sdp, String instructions) {

		@Override
		public String toString() {
			return "CallRequest[sdpChars=" + (sdp == null ? 0 : sdp.length()) + "]";
		}

	}

	/**
	 * @param providerCallId id de la llamada en el proveedor (para colgarla); puede ser nulo si no lo informa
	 * @param sdp            respuesta SDP para el navegador
	 */
	record CreatedCall(String providerCallId, String sdp) {

		@Override
		public String toString() {
			return "CreatedCall[providerCallId=" + providerCallId + "]";
		}

	}

	Info info();

	EventNames events();

	/**
	 * Abre la llamada. Bloquea hasta que el proveedor responde.
	 *
	 * @throws AiCallException si el proveedor falla, responde 4xx/5xx o no responde (el mensaje nunca lleva contenido)
	 */
	CreatedCall createCall(CallRequest request);

	/** Cuelga la llamada en el proveedor. Si falla, lanza {@link AiCallException}; quien llama igual la da por cerrada. */
	void hangup(String providerCallId);

}
