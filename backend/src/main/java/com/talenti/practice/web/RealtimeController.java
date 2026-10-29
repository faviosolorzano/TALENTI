package com.talenti.practice.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.talenti.identity.CurrentUser;
import com.talenti.practice.service.DoraErrors;
import com.talenti.practice.service.RealtimeService;
import com.talenti.practice.service.Views;

/**
 * DORA en tiempo real (spec 012, enmienda 2, contrato de la API): abrir la llamada con la oferta SDP, enviar lo dicho y
 * las etapas, y colgar. Rol, {@code no-store} y tamaño del cuerpo los impone {@link RoleInterceptor}; sesión y CSRF, la
 * cadena de seguridad. Ningún cuerpo se registra: el SDP lleva direcciones IP (012-43, 012-57).
 */
@RestController
@RequestMapping(PracticeWebConfig.DORA + "/sessions/{id}/realtime")
class RealtimeController {

	private final RealtimeService service;

	RealtimeController(RealtimeService service) {
		this.service = service;
	}

	/** @param replacesCallId la llamada que se cortó sin colgar, para reconectar (012-52); opcional */
	record ConnectRequest(String sdp, String replacesCallId) {

		@Override
		public String toString() {
			return "ConnectRequest[...]";
		}

	}

	record EventsRequest(String callId, List<RealtimeService.EventInput> events) {

		@Override
		public String toString() {
			return "EventsRequest[events=" + (events == null ? 0 : events.size()) + "]";
		}

	}

	record HangupRequest(String callId, String reason) {
	}

	@PostMapping
	ResponseEntity<Views.RealtimeCall> connect(@AuthenticationPrincipal CurrentUser user, @PathVariable String id,
			@RequestBody(required = false) ConnectRequest body) {
		UUID sessionId = Ids.session(id);
		UUID replaces = body == null ? null : optionalId(body.replacesCallId());
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(service.connect(user.id(), sessionId, body == null ? null : body.sdp(), replaces));
	}

	@PostMapping("/events")
	Views.EventsResult events(@AuthenticationPrincipal CurrentUser user, @PathVariable String id,
			@RequestBody(required = false) EventsRequest body) {
		UUID sessionId = Ids.session(id);
		if (body == null) {
			throw DoraErrors.invalid("events", "required");
		}
		return service.events(user.id(), sessionId, callId(body.callId()), body.events());
	}

	@PostMapping("/hangup")
	Views.Hangup hangup(@AuthenticationPrincipal CurrentUser user, @PathVariable String id,
			@RequestBody(required = false) HangupRequest body) {
		UUID sessionId = Ids.session(id);
		if (body == null) {
			throw DoraErrors.invalid("callId", "required");
		}
		return service.hangup(user.id(), sessionId, callId(body.callId()), body.reason());
	}

	private static UUID callId(String raw) {
		if (raw == null || raw.isBlank()) {
			throw DoraErrors.invalid("callId", "required");
		}
		return Ids.parse(raw.strip(), DoraErrors.conflict("session-closed"));
	}

	private static UUID optionalId(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw.strip());
		}
		catch (IllegalArgumentException malformed) {
			throw DoraErrors.invalid("replacesCallId", "invalid");
		}
	}

}
