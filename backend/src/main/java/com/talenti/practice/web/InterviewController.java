package com.talenti.practice.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.talenti.identity.CurrentUser;
import com.talenti.practice.service.DoraErrors;
import com.talenti.practice.service.InterviewService;
import com.talenti.practice.service.Views;

/** La entrevista evaluada de una postulación propia (012-23, 012-27). */
@RestController
class InterviewController {

	private final InterviewService service;

	InterviewController(InterviewService service) {
		this.service = service;
	}

	record StartRequest(Boolean noticeAccepted, String noticeVersion) {
	}

	@PostMapping(PracticeWebConfig.INTERVIEW)
	ResponseEntity<Views.Session> create(@AuthenticationPrincipal CurrentUser user, @PathVariable String applicationId,
			@RequestBody(required = false) StartRequest body) {
		UUID id = Ids.parse(applicationId, DoraErrors.notFound("application-not-found"));
		return ResponseEntity.status(HttpStatus.CREATED).body(service.create(user.id(), id,
				body == null ? null : body.noticeAccepted(), body == null ? null : body.noticeVersion()));
	}

	@GetMapping(PracticeWebConfig.INTERVIEW)
	ResponseEntity<Views.InterviewView> view(@AuthenticationPrincipal CurrentUser user,
			@PathVariable String applicationId) {
		UUID id = Ids.parse(applicationId, DoraErrors.notFound("application-not-found"));
		return service.view(user.id(), id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
	}

}
