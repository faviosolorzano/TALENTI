package com.talenti.shared.web;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/** 429 con la cabecera {@code Retry-After} (en segundos). */
public class TooManyRequestsException extends ApiException {

	public static final String CODE = "too-many-requests";

	public TooManyRequestsException(long retryAfterSeconds) {
		super(HttpStatus.TOO_MANY_REQUESTS, CODE, List.of(), retryAfter(retryAfterSeconds));
	}

	private static HttpHeaders retryAfter(long seconds) {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, seconds)));
		return headers;
	}

}
