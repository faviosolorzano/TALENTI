package com.talenti.practice.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * IA y voz de DORA ({@code talenti.ai.dora.*}, 012-31). APAGADA por defecto: con {@code enabled=false} se usan los
 * adaptadores simulados y no sale nada hacia ningún servicio externo. Encendida exige {@code OPENAI_API_KEY} y los tres
 * modelos (si falta algo, el arranque falla con un mensaje claro). Modelos, voz y precios son configuración y deben
 * confirmarse con el proveedor antes de usarlos (012-34).
 *
 * @param enabled usar OpenAI (verdadero) o los simulados (falso, por defecto)
 */
@ConfigurationProperties(prefix = "talenti.ai.dora")
public record DoraAiProperties(
		@DefaultValue("false") boolean enabled,
		@DefaultValue Models models,
		@DefaultValue("") String voice,
		@DefaultValue Prices prices) {

	/** Modelos de conversación, voz a texto y texto a voz (a confirmar). */
	public record Models(
			@DefaultValue("") String dialog,
			@DefaultValue("") String stt,
			@DefaultValue("") String tts) {
	}

	/**
	 * Precios en dólares para estimar el costo de cada llamada (012-30). A CONFIRMAR con la página de precios del
	 * proveedor: son una estimación, no una factura. Los tokens se estiman como caracteres / 4.
	 */
	public record Prices(
			@DefaultValue("0.15") BigDecimal dialogInputPerMillionTokens,
			@DefaultValue("0.60") BigDecimal dialogOutputPerMillionTokens,
			@DefaultValue("0.003") BigDecimal sttPerMinute,
			@DefaultValue("15") BigDecimal ttsPerMillionChars) {
	}

}
