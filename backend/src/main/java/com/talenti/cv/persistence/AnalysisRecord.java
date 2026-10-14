package com.talenti.cv.persistence;

import java.time.Instant;
import java.util.UUID;

/**
 * Una fila de {@code cv_analysis}. Lleva el texto reducido y la huella, así que su {@code toString} solo muestra el id y
 * el estado (nunca contenido).
 */
public record AnalysisRecord(UUID id, UUID userId, String status, String failureReason, int fileSize,
		String fileSha256, String reducedText, boolean inputTruncated, String level, boolean reused, boolean degraded,
		String degradedReason, String adapter, String adapterVersion, String ruleSetVersion, UUID currentReadId,
		Instant createdAt, Instant finishedAt, Instant expiresAt) {

	@Override
	public String toString() {
		return "AnalysisRecord[id=" + id + ", status=" + status + "]";
	}

}
