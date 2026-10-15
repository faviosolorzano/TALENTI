package com.talenti.cv.pdf;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import com.talenti.shared.web.ApiException;

/** 503 {@code cv-busy} con {@code Retry-After}: ya hay el máximo de PDF leyéndose a la vez; se reintenta enseguida. */
public class CvBusyException extends ApiException {

	public static final String CODE = "cv-busy";

	private static final long serialVersionUID = 1L;

	public CvBusyException() {
		super(HttpStatus.SERVICE_UNAVAILABLE, CODE, List.of(), retryAfter(), Map.of());
	}

	private static HttpHeaders retryAfter() {
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.RETRY_AFTER, "5");
		return headers;
	}

}
