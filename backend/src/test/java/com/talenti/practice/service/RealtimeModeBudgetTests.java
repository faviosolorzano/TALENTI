package com.talenti.practice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import com.talenti.practice.adapter.SimulatedRealtimeCalls;
import com.talenti.practice.config.DoraAiProperties;
import com.talenti.practice.config.DoraRealtimeProperties;
import com.talenti.practice.domain.SessionMode;
import com.talenti.practice.persistence.PracticeRepository;
import com.talenti.shared.ai.AiUsageQuota;

class RealtimeModeBudgetTests {

	@Test
	void interviewReservationUsesInterviewPriceEvenWhenCheckingPractice() {
		Instant now = Instant.parse("2026-10-29T12:00:00Z");
		MockEnvironment env = new MockEnvironment().withProperty("talenti.ai.dora.realtime.simulated", "true");
		DoraRealtimeProperties properties = Binder.get(env).bindOrCreate("talenti.ai.dora.realtime", Bindable.of(DoraRealtimeProperties.class));
		DoraAiProperties ai = Binder.get(env).bindOrCreate("talenti.ai.dora", Bindable.of(DoraAiProperties.class));
		PracticeRepository repository = mock(PracticeRepository.class);
		AiUsageQuota quota = mock(AiUsageQuota.class);
		when(quota.monthlySpendRemainingMicros()).thenReturn(1_000_000L);
		SimulatedRealtimeCalls port = new SimulatedRealtimeCalls(null);
		RealtimeService service = new RealtimeService(repository, port, null, null, null, null, null, null, null,
				properties, ai, quota, null, null, Clock.fixed(now, ZoneOffset.UTC));
		// 3 min + 30 s de gracia: entrevista reserva 1.05 USD, práctica 0.35 USD.
		when(repository.openCallEnds(new java.util.UUID(0, 0))).thenReturn(List.of(
				new PracticeRepository.OpenCallBudget(now.plusSeconds(180), SessionMode.INTERVIEW)));
		assertThat(service.availability().realtime().reason()).isEqualTo("SPEND_LIMIT");
		when(repository.openCallEnds(new java.util.UUID(0, 0))).thenReturn(List.of(
				new PracticeRepository.OpenCallBudget(now.plusSeconds(180), SessionMode.PRACTICE)));
		assertThat(service.availability().realtime().available()).isTrue();
	}
}
