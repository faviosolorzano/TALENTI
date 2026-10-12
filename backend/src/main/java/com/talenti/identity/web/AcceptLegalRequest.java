package com.talenti.identity.web;

/** Cuerpo de {@code POST /legal/accept}. */
record AcceptLegalRequest(Boolean legalAccepted, String legalVersion) {
}
