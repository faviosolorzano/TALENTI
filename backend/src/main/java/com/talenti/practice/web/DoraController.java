package com.talenti.practice.web;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.talenti.identity.CurrentUser;
import com.talenti.practice.service.DoraErrors;
import com.talenti.practice.service.DoraService;
import com.talenti.practice.service.Views;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * La conversación con DORA (spec 012, sección 6). {@code start} y {@code turns} responden un flujo SSE
 * ({@code text/event-stream}): todo error de validación sale ANTES, como Problem Details. El flujo corre en un hilo
 * virtual. Por qué SSE y no WebSocket: un pedido por turno reutiliza la cookie, el CSRF y la autorización (sección 6).
 */
@RestController
@RequestMapping(PracticeWebConfig.DORA + "/sessions/{id}")
class DoraController {

	/** Un turno con voz no debería pasar de unos segundos; esto es solo un tope de seguridad. */
	private static final long STREAM_TIMEOUT_MS = 120_000;

	private final DoraService service;

	private final ExecutorService executor;

	private final JsonMapper mapper;

	DoraController(DoraService service, @Qualifier("doraExecutor") ExecutorService executor, JsonMapper mapper) {
		this.service = service;
		this.executor = executor;
		this.mapper = mapper;
	}

	record TextTurnRequest(String text, Integer questionPosition, String clientTurnId) {

		@Override
		public String toString() {
			return "TextTurnRequest[...]";
		}

	}

	record CorrectionRequest(String transcript) {

		@Override
		public String toString() {
			return "CorrectionRequest[...]";
		}

	}

	@PostMapping(value = "/start", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter start(@AuthenticationPrincipal CurrentUser user, @PathVariable String id, HttpServletResponse response) {
		return stream(service.start(user.id(), Ids.session(id)), response);
	}

	@PostMapping(value = "/turns", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter audioTurn(@AuthenticationPrincipal CurrentUser user, @PathVariable String id,
			@RequestPart(name = "audio", required = false) MultipartFile audio,
			@RequestParam(required = false) String questionPosition, @RequestParam(required = false) String clientTurnId,
			HttpServletResponse response) throws IOException {
		long received = System.nanoTime();
		UUID sessionId = Ids.session(id);
		if (audio == null || audio.isEmpty()) {
			throw DoraErrors.invalid("audio", "required");
		}
		DoraService.TurnCommand command = new DoraService.TurnCommand(audio.getBytes(), null,
				position(questionPosition), clientTurnId(clientTurnId));
		return stream(service.turn(user.id(), sessionId, command, received), response);
	}

	@PostMapping(value = "/turns", consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter textTurn(@AuthenticationPrincipal CurrentUser user, @PathVariable String id,
			@RequestBody(required = false) TextTurnRequest body, HttpServletResponse response) {
		long received = System.nanoTime();
		UUID sessionId = Ids.session(id);
		if (body == null) {
			throw DoraErrors.invalid("text", "required");
		}
		DoraService.TurnCommand command = new DoraService.TurnCommand(null, body.text(), body.questionPosition(),
				clientTurnId(body.clientTurnId()));
		return stream(service.turn(user.id(), sessionId, command, received), response);
	}

	@PatchMapping("/turns/{turnId}")
	Views.Turn correct(@AuthenticationPrincipal CurrentUser user, @PathVariable String id, @PathVariable String turnId,
			@RequestBody(required = false) CorrectionRequest body) {
		UUID sessionId = Ids.session(id);
		UUID turn = Ids.parse(turnId, DoraErrors.notFound("turn-not-found"));
		return service.correct(user.id(), sessionId, turn, body == null ? null : body.transcript());
	}

	@PostMapping("/turns/{turnId}/feedback")
	ResponseEntity<Void> retryFeedback(@AuthenticationPrincipal CurrentUser user, @PathVariable String id,
			@PathVariable String turnId) {
		UUID sessionId = Ids.session(id);
		service.retryFeedback(user.id(), sessionId, Ids.parse(turnId, DoraErrors.notFound("turn-not-found")));
		return ResponseEntity.status(HttpStatus.ACCEPTED).build();
	}

	@GetMapping("/questions/{position}/audio")
	ResponseEntity<byte[]> questionAudio(@AuthenticationPrincipal CurrentUser user, @PathVariable String id,
			@PathVariable String position) {
		UUID sessionId = Ids.session(id);
		int number;
		try {
			number = Integer.parseInt(position);
		}
		catch (NumberFormatException malformed) {
			throw DoraErrors.notFound("question-not-found");
		}
		DoraService.QuestionAudio audio = service.questionAudio(user.id(), sessionId, number);
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(audio.mime()))
				.header(HttpHeaders.CACHE_CONTROL, "no-store").body(audio.data());
	}

	@PostMapping("/finish")
	Views.Session finish(@AuthenticationPrincipal CurrentUser user, @PathVariable String id) {
		return service.finish(user.id(), Ids.session(id));
	}

	// ------------------------------------------------------------------------------------------ piezas

	private SseEmitter stream(DoraService.Stream stream, HttpServletResponse response) {
		// Sin búfer en proxies (nginx) para que cada evento llegue al instante.
		response.setHeader("X-Accel-Buffering", "no");
		response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
		SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
		SseEventSink sink = new SseEventSink(emitter, mapper);
		executor.execute(() -> stream.run(sink));
		return emitter;
	}

	private static Integer position(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return Integer.parseInt(raw.strip());
		}
		catch (NumberFormatException malformed) {
			throw DoraErrors.invalid("questionPosition", "invalid");
		}
	}

	private static UUID clientTurnId(String raw) {
		if (raw == null || raw.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(raw.strip());
		}
		catch (IllegalArgumentException malformed) {
			throw DoraErrors.invalid("clientTurnId", "invalid");
		}
	}

}
