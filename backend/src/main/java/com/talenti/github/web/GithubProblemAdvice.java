package com.talenti.github.web;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.talenti.github.client.GithubNotFoundException;
import com.talenti.github.client.GithubRateLimitedException;
import com.talenti.github.client.GithubUnavailableException;
import com.talenti.github.config.GithubProperties;
import com.talenti.shared.web.ProblemMessages;

/**
 * Las fallas de GitHub en {@code /github} (013-19, 013-20): 503 en Problem Details, en español, nunca 500.
 * {@code github-rate-limited} lleva {@code retryAt}, la cabecera {@code Retry-After} y la hora en el texto
 * ("vuelve a intentarlo a las 15:40"); {@code github-unavailable} lleva {@code retryable: true}.
 */
@RestControllerAdvice(assignableTypes = GithubController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class GithubProblemAdvice {

	private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");

	private final ProblemMessages messages;

	private final GithubProperties properties;

	private final Clock clock;

	GithubProblemAdvice(ProblemMessages messages, GithubProperties properties, Clock clock) {
		this.messages = messages;
		this.properties = properties;
		this.clock = clock;
	}

	@ExceptionHandler(GithubRateLimitedException.class)
	ResponseEntity<ProblemDetail> rateLimited(GithubRateLimitedException ex) {
		Instant retryAt = ex.retryAt();
		ProblemDetail problem = problem("github-rate-limited", HOUR.format(retryAt.atZone(properties.zone())));
		problem.setProperty("retryAt", retryAt.toString());
		long seconds = Math.max(1, Duration.between(clock.instant(), retryAt).toSeconds() + 1);
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.RETRY_AFTER, Long.toString(seconds))
				.header(HttpHeaders.CACHE_CONTROL, "no-store").body(problem);
	}

	@ExceptionHandler({ GithubUnavailableException.class, GithubNotFoundException.class })
	ResponseEntity<ProblemDetail> unavailable(RuntimeException ex) {
		ProblemDetail problem = problem("github-unavailable");
		problem.setProperty("retryable", true);
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.CACHE_CONTROL, "no-store")
				.body(problem);
	}

	private ProblemDetail problem(String code, Object... args) {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
		problem.setType(URI.create("urn:talenti:problem:" + code));
		problem.setTitle(messages.title(code));
		problem.setDetail(messages.detail(code, args));
		problem.setProperty("code", code);
		return problem;
	}

}
