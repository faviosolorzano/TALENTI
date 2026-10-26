package com.talenti.shared.web;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Todos los errores de la API salen como Problem Details (RFC 9457): {@code type}, {@code title},
 * {@code status}, {@code detail}, un {@code code} estable y, en 422, {@code errors[{field, rule, message}]}.
 * Texto en español, sin trazas, sin nombres de excepción y sin datos de personas.
 */
@RestControllerAdvice
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private final ProblemMessages messages;

	GlobalExceptionHandler(ProblemMessages messages) {
		this.messages = messages;
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
		ProblemDetail problem = problem(ex.getStatus(), ex.getCode(), ex.getViolations());
		if (!ex.getMessageKey().equals(ex.getCode())) {
			problem.setTitle(messages.title(ex.getMessageKey()));
			problem.setDetail(messages.detail(ex.getMessageKey()));
		}
		ex.getProperties().forEach(problem::setProperty);
		return ResponseEntity.status(ex.getStatus()).headers(ex.getHeaders()).body(problem);
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(problem(HttpStatus.UNAUTHORIZED, "unauthenticated", List.of()));
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(problem(HttpStatus.FORBIDDEN, "access-denied", List.of()));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
		// Solo los nombres de las clases: el mensaje de una excepción puede traer datos personales.
		Throwable root = rootCause(ex);
		log.error("Error no controlado: {} (causa raíz: {})", ex.getClass().getName(), root.getClass().getName());
		log.debug("Detalle del error no controlado", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", List.of()));
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> toViolation(error))
				.toList();
		ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_ENTITY, ValidationFailedException.CODE, violations);
		return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).headers(headers).body(problem);
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "malformed-request", List.of());
		return ResponseEntity.status(HttpStatus.BAD_REQUEST).headers(headers).body(problem);
	}

	/** Errores estándar de Spring MVC (404, 405, 415...): se les pone código y texto en español. */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (body instanceof ProblemDetail original) {
			decorate(original, codeFor(statusCode), List.of());
		}
		return super.handleExceptionInternal(ex, body, headers, statusCode, request);
	}

	private ProblemDetail problem(HttpStatusCode status, String code, List<FieldViolation> violations) {
		ProblemDetail problem = ProblemDetail.forStatus(status);
		decorate(problem, code, violations);
		return problem;
	}

	private void decorate(ProblemDetail problem, String code, List<FieldViolation> violations) {
		problem.setType(URI.create("urn:talenti:problem:" + code));
		problem.setTitle(messages.title(code));
		problem.setDetail(messages.detail(code));
		problem.setProperty("code", code);
		if (!violations.isEmpty()) {
			problem.setProperty("errors", violations.stream().map(this::toError).toList());
		}
	}

	private Map<String, Object> toError(FieldViolation violation) {
		Map<String, Object> error = new LinkedHashMap<>();
		error.put("field", violation.field());
		error.put("rule", violation.rule());
		error.put("message", messages.violation(violation));
		return error;
	}

	private static String codeFor(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> "malformed-request";
			case 404 -> "not-found";
			case 405 -> "method-not-allowed";
			case 406 -> "not-acceptable";
			case 413 -> "payload-too-large";
			case 415 -> "unsupported-media-type";
			default -> status.is5xxServerError() ? "internal-error" : "request-error";
		};
	}

	private static FieldViolation toViolation(org.springframework.validation.FieldError error) {
		String rule = ruleOf(error.getCode());
		if ("too-long".equals(rule) && error.getArguments() != null) {
			// En @Size el segundo argumento es el máximo permitido; el texto lo muestra ("máximo 254 caracteres").
			for (Object argument : error.getArguments()) {
				if (argument instanceof Integer max && max < Integer.MAX_VALUE) {
					return FieldViolation.of(error.getField(), rule, max);
				}
			}
		}
		return FieldViolation.of(error.getField(), rule);
	}

	private static String ruleOf(@Nullable String constraintCode) {
		if (constraintCode == null) {
			return "invalid";
		}
		return switch (constraintCode) {
			case "NotBlank", "NotNull", "NotEmpty" -> "required";
			case "Size" -> "too-long";
			default -> constraintCode.toLowerCase(java.util.Locale.ROOT);
		};
	}

	private static Throwable rootCause(Throwable ex) {
		Throwable current = ex;
		while (current.getCause() != null && current.getCause() != current) {
			current = current.getCause();
		}
		return current;
	}

}
