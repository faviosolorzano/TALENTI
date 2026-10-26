package com.talenti.exam.web;

import java.time.Instant;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import com.talenti.identity.CurrentUser;
import com.talenti.exam.domain.AttemptRules;
import com.talenti.exam.persistence.ExamStore;
import com.talenti.exam.service.*;

@RestController
@RequestMapping("/api/v1")
class ExamController {
	private final ExamAttemptService attempts;
	private final ExamAuthoringService authoring;
	private final ExamStore store;
	ExamController(ExamAttemptService attempts,ExamAuthoringService authoring,ExamStore store) { this.attempts=attempts; this.authoring=authoring; this.store=store; }
	record Notice(Boolean noticeAccepted,String noticeVersion) { }
	record Event(String kind,String clientEventId,String clientOccurredAt) {
		@Override public String toString() { return "ExamEventRequest[...]"; }
	}
	private static UUID id(String value) { try { return UUID.fromString(value); } catch(IllegalArgumentException e) { throw ExamErrors.missing(); } }
	@PostMapping("/applications/{application}/exam")
	ResponseEntity<?> create(@AuthenticationPrincipal CurrentUser user,@PathVariable String application,@RequestBody Notice notice) {
		var result=attempts.create(user.id(),id(application),Boolean.TRUE.equals(notice.noticeAccepted()),notice.noticeVersion());
		return result.preparing()!=null?ResponseEntity.accepted().header("Retry-After","2").body(result.preparing())
				:ResponseEntity.status(201).body(result.attempt());
	}
	@GetMapping("/applications/{application}/exam")
	ResponseEntity<?> forApplication(@AuthenticationPrincipal CurrentUser user,@PathVariable String application) {
		return attempts.forApplication(user.id(),id(application)).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(()->ResponseEntity.noContent().build());
	}
	@GetMapping("/exam-attempts/{attempt}")
	ExamViews.Attempt get(@AuthenticationPrincipal CurrentUser user,@PathVariable String attempt) { return attempts.get(user.id(),id(attempt)); }
	@PostMapping("/exam-attempts/{attempt}/start")
	ExamViews.Attempt start(@AuthenticationPrincipal CurrentUser user,@PathVariable String attempt) { return attempts.start(user.id(),id(attempt)); }
	@PutMapping("/exam-attempts/{attempt}/answers/{question}")
	ExamViews.Save save(@AuthenticationPrincipal CurrentUser user,@PathVariable String attempt,@PathVariable String question,@RequestBody ExamAttemptService.AnswerCommand command) {
		return attempts.save(user.id(),id(attempt),id(question),command);
	}
	@PostMapping("/exam-attempts/{attempt}/answers/{question}/run")
	ExamViews.Run run(@AuthenticationPrincipal CurrentUser user,@PathVariable String attempt,@PathVariable String question) { return attempts.run(user.id(),id(attempt),id(question)); }
	@PostMapping("/exam-attempts/{attempt}/submit")
	ExamViews.Attempt submit(@AuthenticationPrincipal CurrentUser user,@PathVariable String attempt) { return attempts.submit(user.id(),id(attempt)); }
	@PostMapping("/exam-attempts/{attempt}/events")
	ExamViews.EventReply event(@AuthenticationPrincipal CurrentUser user,@PathVariable String attempt,@RequestBody Event event) {
		AttemptRules.EventKind kind; UUID client; Instant occurred;
		try { kind=AttemptRules.EventKind.valueOf(event.kind()); client=UUID.fromString(event.clientEventId()); occurred=Instant.parse(event.clientOccurredAt()); }
		catch(RuntimeException e) { throw ExamErrors.invalid("event","invalid-event"); }
		return attempts.event(user.id(),id(attempt),kind,client,occurred);
	}
	@PostMapping("/admin/vacancies/{vacancy}/exam-set")
	ResponseEntity<?> prepare(@PathVariable String vacancy) {
		var set=authoring.ensure(id(vacancy)); return ResponseEntity.accepted().body(Map.of("id",set.id(),"status",set.status()));
	}
	@GetMapping("/admin/vacancies/{vacancy}/exam-set")
	ResponseEntity<?> examSet(@PathVariable String vacancy) {
		var set=authoring.forVacancy(id(vacancy));
		if(set.isEmpty()) throw ExamErrors.missing();
		var versions=store.versions(set.get().id()).stream().map(v->Map.of("id",v.id(),"label",v.label(),"locked",v.locked(),
				"verificationReport",v.verificationJson(),"questions",store.questions(v.id()))).toList();
		return ResponseEntity.ok(Map.of("set",set.get(),"versions",versions));
	}
}
