package com.talenti.practice.service;

/**
 * El aviso vigente de la entrevista evaluada (012-23), versionado en
 * {@code classpath:practice/interview-notice-<versión>.md}. Borrador pendiente de revisión legal (duda 13).
 */
public record InterviewNotice(String version, String text) {

	@Override
	public String toString() {
		return "InterviewNotice[version=" + version + "]";
	}

}
