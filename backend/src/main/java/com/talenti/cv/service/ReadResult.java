package com.talenti.cv.service;

import java.util.List;

import com.talenti.cv.AdapterInfo;
import com.talenti.cv.domain.SuggestionDraft;
import com.talenti.shared.ai.AiLevel;

/**
 * Resultado de una lectura. Si {@code keepPrevious} es verdadero la lectura mejorada falló y se conservan las
 * sugerencias anteriores (009-27).
 *
 * @param degraded motivo si la persona pidió la lectura mejorada y se atendió con la básica; nulo si no
 */
public record ReadResult(List<SuggestionDraft> drafts, boolean keepPrevious, AiLevel level, AdapterInfo info,
		String degraded, boolean inputTruncated) {

	static ReadResult keep(String degradedReason) {
		return new ReadResult(null, true, null, null, degradedReason, false);
	}

	/** Contenido del CV: no se imprime en registros. */
	@Override
	public String toString() {
		return "ReadResult[level=" + level + ", keepPrevious=" + keepPrevious + "]";
	}

}
