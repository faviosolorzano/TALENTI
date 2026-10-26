package com.talenti.practice.web;

import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.talenti.identity.CurrentUser;
import com.talenti.practice.domain.Basis;
import com.talenti.practice.service.DoraErrors;
import com.talenti.practice.service.PracticeService;
import com.talenti.practice.service.Views;

/**
 * Sesión de práctica (spec 012, sección 6): opciones, crear, historial, detalle y borrado. El id de la persona sale
 * siempre de la sesión. Rol y {@code no-store} los impone {@link RoleInterceptor}.
 */
@RestController
@RequestMapping(PracticeWebConfig.PRACTICE)
class PracticeController {

	private final PracticeService service;

	PracticeController(PracticeService service) {
		this.service = service;
	}

	/** Llega como texto para responder 422 con el campo exacto en vez de un 400 genérico. */
	record CreateRequest(String basis, String vacancyId, String topic, Integer questionCount, Boolean voice) {

		@Override
		public String toString() {
			return "CreateRequest[...]";
		}

	}

	@GetMapping("/options")
	Views.Options options(@AuthenticationPrincipal CurrentUser user) {
		return service.options(user.id());
	}

	@PostMapping("/sessions")
	ResponseEntity<Views.Session> create(@AuthenticationPrincipal CurrentUser user,
			@RequestBody(required = false) CreateRequest body) {
		if (body == null || body.basis() == null || body.basis().isBlank()) {
			throw DoraErrors.invalid("basis", "required");
		}
		Basis basis;
		try {
			basis = Basis.valueOf(body.basis().strip().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException unknown) {
			throw DoraErrors.invalid("basis", "invalid");
		}
		if (body.questionCount() == null) {
			throw DoraErrors.invalid("questionCount", "required");
		}
		UUID vacancyId = null;
		if (body.vacancyId() != null && !body.vacancyId().isBlank()) {
			vacancyId = Ids.parse(body.vacancyId().strip(), DoraErrors.notFound("vacancy-not-found"));
		}
		String topic = body.topic() == null || body.topic().isBlank() ? null : body.topic().strip();
		PracticeService.CreateCommand command = new PracticeService.CreateCommand(basis,
				basis == Basis.VACANCY ? vacancyId : null, topic, body.questionCount(),
				body.voice() == null || body.voice());
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user.id(), command));
	}

	@GetMapping("/sessions")
	Views.SessionPage list(@AuthenticationPrincipal CurrentUser user, @RequestParam(required = false) String page,
			@RequestParam(required = false) String size) {
		int pageNumber = number("page", page, 1, 1, 10_000);
		int pageSize = number("size", size, PracticeService.DEFAULT_PAGE_SIZE, 1, PracticeService.MAX_PAGE_SIZE);
		return service.list(user.id(), pageNumber, pageSize);
	}

	@GetMapping("/sessions/{id}")
	Views.Session detail(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
		return service.detail(user.id(), Ids.session(id));
	}

	@DeleteMapping("/sessions/{id}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
		service.delete(user.id(), Ids.session(id));
		return ResponseEntity.noContent().build();
	}

	private static int number(String field, String raw, int fallback, int min, int max) {
		if (raw == null || raw.isBlank()) {
			return fallback;
		}
		int value;
		try {
			value = Integer.parseInt(raw.strip());
		}
		catch (NumberFormatException notANumber) {
			throw DoraErrors.invalid(field, "invalid");
		}
		if (value < min || value > max) {
			throw DoraErrors.invalid(field, "out-of-range", min, max);
		}
		return value;
	}

}
