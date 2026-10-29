package com.talenti.matching.port;

import com.talenti.matching.domain.ExplanationDraft;
import com.talenti.matching.domain.ExplanationRequest;

/**
 * Puerto de la IA que redacta "DORA te explica" (spec 015, principio 8). El dominio no conoce al proveedor: hay un
 * adaptador simulado ({@code SimulatedCompatibilityExplainer}) y uno de OpenAI (apagado por defecto). Es propio de
 * matching: no usa nada de practice (T5, 015-18).
 *
 * <p>Solo recibe {@link ExplanationRequest} (sin porcentaje ni datos personales, 015-04). Lo que devuelve es un borrador
 * SIN VALIDAR: el servicio siempre lo valida antes de mostrarlo (015-07, 015-08). La llamada bloquea; quien llama impone
 * el tiempo máximo y la interrumpe si se agota. Falla con {@link ExplainerException}.
 */
public interface CompatibilityExplainerPort {

	/** Nombre estable del adaptador, por ejemplo {@code simulated} u {@code openai}. */
	String adapter();

	/** Modelo de IA; nulo si el adaptador no tiene uno. */
	String model();

	/** Versión de las instrucciones (principio 11). Entra en la huella del caché (015-12). */
	String promptVersion();

	/** Caracteres de las instrucciones fijas que acompañan a cada petición (para estimar el costo, 015-14). */
	int instructionChars();

	Answer explain(ExplanationRequest request);

	/**
	 * @param outputChars caracteres de la respuesta recibida (para estimar el costo)
	 */
	record Answer(ExplanationDraft draft, int outputChars) {
	}

}
