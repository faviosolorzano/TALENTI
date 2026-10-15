package com.talenti.application.web;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.talenti.application.service.ApplicationService;
import com.talenti.application.service.Views;
import com.talenti.identity.CurrentUser;
import com.talenti.shared.web.ApiException;
import com.talenti.shared.web.FieldViolation;
import com.talenti.shared.web.ValidationFailedException;

/**
 * Postulaciones del estudiante (spec 008, sección 6). El id de la persona sale siempre de la sesión: ninguna ruta
 * recibe el id de un usuario (008-10). El rol y {@code Cache-Control: no-store} los impone
 * {@link StudentOnlyInterceptor} a toda la ruta.
 */
@RestController
@RequestMapping(ApplicationWebConfig.APPLICATIONS)
class ApplicationController {

	private final ApplicationService service;

	ApplicationController(ApplicationService service) {
		this.service = service;
	}

	/** {@code vacancyId} llega como texto: uno mal formado es un 404 igual que uno inexistente (008-05). */
	record SubmitRequest(String vacancyId) {

		@Override
		public String toString() {
			return "SubmitRequest[...]";
		}

	}

	@PostMapping
	ResponseEntity<Views.Application> submit(@AuthenticationPrincipal CurrentUser user,
			@RequestBody(required = false) SubmitRequest body) {
		if (body == null || body.vacancyId() == null || body.vacancyId().isBlank()) {
			throw new ValidationFailedException(List.of(FieldViolation.of("vacancyId", "required")));
		}
		UUID vacancyId;
		try {
			vacancyId = UUID.fromString(body.vacancyId().strip());
		}
		catch (IllegalArgumentException malformed) {
			throw new ApiException(HttpStatus.NOT_FOUND, "vacancy-not-found");
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(service.submit(user.id(), vacancyId));
	}

	@GetMapping
	Views.Page list(@AuthenticationPrincipal CurrentUser user, @RequestParam(required = false) String page,
			@RequestParam(required = false) String size, @RequestParam(required = false) String vacancyId) {
		List<FieldViolation> violations = new ArrayList<>();
		int pageNumber = integer("page", page, 1, 1, Integer.MAX_VALUE, violations);
		int pageSize = integer("size", size, ApplicationService.DEFAULT_PAGE_SIZE, 1, ApplicationService.MAX_PAGE_SIZE,
				violations);
		UUID vacancy = null;
		if (vacancyId != null && !vacancyId.isBlank()) {
			try {
				vacancy = UUID.fromString(vacancyId.strip());
			}
			catch (IllegalArgumentException malformed) {
				violations.add(FieldViolation.of("vacancyId", "invalid"));
			}
		}
		if (!violations.isEmpty()) {
			throw new ValidationFailedException(violations);
		}
		return service.list(user.id(), vacancy, pageNumber, pageSize);
	}

	@GetMapping("/summary")
	Views.Summary summary(@AuthenticationPrincipal CurrentUser user) {
		return service.summary(user.id());
	}

	@GetMapping("/{id}")
	Views.Application detail(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
		return service.detail(user.id(), parseId(id));
	}

	@PostMapping("/{id}/withdraw")
	Views.Application withdraw(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
		return service.withdraw(user.id(), parseId(id));
	}

	private static UUID parseId(String raw) {
		try {
			return UUID.fromString(raw);
		}
		catch (IllegalArgumentException malformed) {
			throw new ApiException(HttpStatus.NOT_FOUND, "application-not-found");
		}
	}

	private static int integer(String field, String raw, int fallback, int min, int max,
			List<FieldViolation> violations) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		try {
			int value = Integer.parseInt(raw.strip());
			if (value < min || value > max) {
				violations.add(FieldViolation.of(field, "out-of-range", min, max));
				return fallback;
			}
			return value;
		}
		catch (NumberFormatException notANumber) {
			violations.add(FieldViolation.of(field, "invalid"));
			return fallback;
		}
	}

}
