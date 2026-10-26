package com.talenti.practice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.talenti.practice.config.DoraProperties;
import com.talenti.practice.domain.InputMode;
import com.talenti.practice.domain.SessionMode;
import com.talenti.practice.domain.SessionStatus;
import com.talenti.practice.persistence.PracticeRepository;
import com.talenti.shared.web.ApiException;

class DoraCorrectionRegressionTests {

	private final PracticeRepository repository = mock(PracticeRepository.class);
	private final FeedbackService feedback = mock(FeedbackService.class);
	private final SessionLifecycle lifecycle = mock(SessionLifecycle.class);
	private final DoraProperties properties = mock(DoraProperties.class);
	private final UUID user = UUID.randomUUID(), sessionId = UUID.randomUUID(), turnId = UUID.randomUUID();
	private final PracticeRepository.SessionRow session = mock(PracticeRepository.SessionRow.class);
	private final Instant now = Instant.parse("2026-10-26T15:00:00Z");
	private DoraService service;

	@BeforeEach
	void setup() {
		service = new DoraService(repository, null, null, lifecycle, feedback, null, null, null, null, properties,
				Clock.fixed(now, ZoneOffset.UTC), null);
		when(properties.maxTranscript()).thenReturn(4000);
		when(repository.findOwned(sessionId, user)).thenReturn(Optional.of(session));
		when(session.mode()).thenReturn(SessionMode.INTERVIEW);
		when(session.status()).thenReturn(SessionStatus.COMPLETED);
		when(repository.findTurn(turnId, sessionId)).thenReturn(Optional.of(new PracticeRepository.TurnRow(turnId,
				UUID.randomUUID(), sessionId, 5, UUID.randomUUID(), 1, InputMode.VOICE, "Original", "Original",
				1000, 10, 20, 30, "LLM", null, now)));
	}

	@Test
	void t012_13_lastInterviewTranscriptCanBeCorrectedAndReevaluated() {
		var corrected = service.correct(user, sessionId, turnId, " Corrección final ");
		assertThat(corrected.transcriptRaw()).isEqualTo("Original");
		assertThat(corrected.transcriptFinal()).isEqualTo("Corrección final");
		assertThat(corrected.feedbackStatus()).isNull(); // la evaluación de entrevista no se revela al estudiante
		verify(repository).correctTranscript(turnId, "Corrección final", now);
		verify(feedback).schedule(sessionId, turnId, user, SessionMode.INTERVIEW);
		verify(lifecycle).event(eq(session), eq("TRANSCRIPT_CORRECTED"), anyMap());
		verify(repository, never()).touch(any(), any());
		verify(repository, never()).close(any(), any(), any());
	}

	@Test
	void t012_13_openInterviewIsStillEvaluatedOnlyOnCompletion() {
		when(session.status()).thenReturn(SessionStatus.IN_PROGRESS);
		service.correct(user, sessionId, turnId, "Corrección");
		verifyNoInteractions(feedback);
		verify(repository).touch(sessionId, now);
	}

	@Test
	void t012_13_failedAndAbandonedInterviewsCannotChange() {
		for (SessionStatus status : new SessionStatus[] { SessionStatus.FAILED, SessionStatus.ABANDONED }) {
			when(session.status()).thenReturn(status);
			assertThatThrownBy(() -> service.correct(user, sessionId, turnId, "Corrección"))
					.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("session-closed"));
		}
		verify(repository, never()).correctTranscript(any(), anyString(), any());
		verifyNoInteractions(feedback);
	}

	@Test
	void t012_32_completedInterviewStillChecksOwnership() {
		when(repository.findOwned(sessionId, user)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.correct(user, sessionId, turnId, "Corrección"))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
		verify(repository, never()).findTurn(any(), any());
		verifyNoInteractions(feedback);
	}

	@Test
	void t012_13_practiceCorrectionKeepsItsFeedbackContract() {
		when(session.mode()).thenReturn(SessionMode.PRACTICE);
		assertThat(service.correct(user, sessionId, turnId, "Corrección").feedbackStatus()).isEqualTo("PENDING");
		verify(feedback).schedule(sessionId, turnId, user, SessionMode.PRACTICE);
	}
}
