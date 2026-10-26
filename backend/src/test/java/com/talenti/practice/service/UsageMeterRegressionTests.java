package com.talenti.practice.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import java.util.concurrent.ExecutorService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.talenti.practice.config.DoraAiProperties;
import com.talenti.practice.domain.SessionMode;
import com.talenti.practice.port.ConversationPort;
import com.talenti.shared.ai.*;

class UsageMeterRegressionTests {

	@Test
	void t012_29_platformFailureIsAnIdempotentCompensationForEitherMode() {
		for (SessionMode mode : SessionMode.values()) {
			var recorder = mock(AiUsageRecorder.class);
			var conversation = mock(ConversationPort.class);
			when(conversation.info()).thenReturn(new ConversationPort.Info("simulated", "llm", "stt", "tts", "voice", "v1"));
			var meter = new UsageMeter(recorder, mock(AiUsageQuota.class),
					new DoraAiProperties(false, null, "", null), mock(ExecutorService.class), conversation);
			UUID session = UUID.randomUUID(), user = UUID.randomUUID();
			meter.sessionUsed(session, user, mode, AiUsageOutcome.SUCCESS);
			meter.sessionUsed(session, user, mode, AiUsageOutcome.FAILED_PLATFORM);
			meter.sessionUsed(session, user, mode, AiUsageOutcome.FAILED_PLATFORM);
			var captured = ArgumentCaptor.forClass(AiUsageEvent.class);
			verify(recorder, times(3)).record(captured.capture());
			var events = captured.getAllValues();
			assertThat(events.get(0).readId()).isEqualTo(session);
			assertThat(events.get(0).reversesReadId()).isNull();
			assertThat(events.get(1).readId()).isNotEqualTo(session);
			assertThat(events.get(1).reversesReadId()).isEqualTo(session);
			assertThat(events.get(1)).isEqualTo(events.get(2));
			assertThat(events.get(1).function()).isEqualTo(mode == SessionMode.PRACTICE
					? AiFunction.PRACTICE_SESSION : AiFunction.INTERVIEW_SESSION);
		}
	}
}
