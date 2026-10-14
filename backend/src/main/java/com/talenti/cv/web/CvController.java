package com.talenti.cv.web;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.multipart.MultipartFile;

import com.talenti.cv.service.AnalysisView;
import com.talenti.cv.service.CvAnalysisService;
import com.talenti.cv.service.DecisionInput;
import com.talenti.cv.service.OriginRateLimiter;
import com.talenti.cv.service.StartedRead;
import com.talenti.cv.service.UsageView;
import com.talenti.identity.CurrentUser;
import com.talenti.profile.ProfileView;
import com.talenti.shared.web.FieldViolation;
import com.talenti.shared.web.ValidationFailedException;

/**
 * Lector de CV (spec 009, sección 6). El id de la persona sale siempre de la sesión: ninguna ruta recibe el id de un
 * usuario (009-17). El rol y {@code Cache-Control: no-store} los impone {@link CvAccessInterceptor} a toda la ruta. Ni
 * esta clase ni ninguna ruta devuelve el PDF ni su nombre; ninguna lee un CV al consultar (009-25).
 */
@RestController
@RequestMapping(CvWebConfig.CV)
class CvController {

	private static final Pattern ORIGIN = Pattern.compile("[0-9a-fA-F:.]{2,45}");

	private final CvAnalysisService service;

	private final OriginRateLimiter rateLimiter;

	CvController(CvAnalysisService service, OriginRateLimiter rateLimiter) {
		this.service = service;
		this.rateLimiter = rateLimiter;
	}

	@GetMapping("/usage")
	UsageView usage(@AuthenticationPrincipal CurrentUser user) {
		return service.usage(user.id());
	}

	@PostMapping(value = "/analyses", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	ResponseEntity<StartedRead> upload(@AuthenticationPrincipal CurrentUser user, HttpServletRequest request,
			@RequestParam(name = "file", required = false) MultipartFile file,
			@RequestParam(name = "mode", required = false) String mode) throws IOException {
		rateLimiter.check(origin(request));
		if (file == null || file.isEmpty()) {
			throw new ValidationFailedException(List.of(FieldViolation.of("file", "required")));
		}
		StartedRead started = service.upload(user.id(), file.getBytes(), file.getContentType(), mode);
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(started);
	}

	@PostMapping("/analyses/{id}/reads")
	ResponseEntity<StartedRead> read(@AuthenticationPrincipal CurrentUser user, HttpServletRequest request,
			@PathVariable String id, @RequestBody(required = false) ReadRequest body) {
		rateLimiter.check(origin(request));
		StartedRead started = service.requestRead(user.id(), CvAnalysisService.parseId(id),
				body == null ? null : body.mode());
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(started);
	}

	@GetMapping("/analyses/current")
	ResponseEntity<AnalysisView> current(@AuthenticationPrincipal CurrentUser user) {
		return service.current(user.id()).map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.noContent().build());
	}

	@PostMapping("/analyses/{id}/confirm")
	ProfileView confirm(@AuthenticationPrincipal CurrentUser user, @PathVariable String id,
			@RequestBody ConfirmRequest body) {
		return service.confirm(user.id(), CvAnalysisService.parseId(id), body.decisions());
	}

	@DeleteMapping("/analyses/{id}")
	ResponseEntity<Void> discard(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
		service.discard(user.id(), CvAnalysisService.parseId(id));
		return ResponseEntity.noContent().build();
	}

	/** {@code mode}: {@code BASIC} (por defecto) o {@code ENHANCED}. */
	record ReadRequest(String mode) {

		@Override
		public String toString() {
			return "ReadRequest[...]";
		}

	}

	record ConfirmRequest(List<DecisionInput> decisions) {

		@Override
		public String toString() {
			return "ConfirmRequest[decisions=" + (decisions == null ? 0 : decisions.size()) + "]";
		}

	}

	/** El origen para el límite de solicitudes: la cabecera que pone nginx si es una IP literal, si no la conexión. */
	private static String origin(HttpServletRequest request) {
		String header = request.getHeader("X-Real-IP");
		if (header != null && ORIGIN.matcher(header.strip()).matches()) {
			return header.strip();
		}
		return request.getRemoteAddr();
	}

}
