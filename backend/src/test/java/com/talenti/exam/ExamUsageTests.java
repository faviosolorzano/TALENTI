package com.talenti.exam;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import com.talenti.exam.config.ExamAiProperties;
import com.talenti.exam.service.ExamUsage;
import com.talenti.shared.ai.*;
import tools.jackson.databind.json.JsonMapper;

class ExamUsageTests {
	@Test void t014_32_onlyGlobalSpendAppliesAndMeasurementNeverCarriesContent() {
		var quota=mock(AiUsageQuota.class);var events=new ArrayList<AiUsageEvent>();
		AiUsageRecorder recorder=new AiUsageRecorder() {
			public boolean record(AiUsageEvent event) { events.add(event);return true; }
			public boolean exists(UUID id) { return false; }
		};
		var usage=new ExamUsage(quota,recorder,new ExamAiProperties(true,"test-model",BigDecimal.valueOf(1000000),BigDecimal.valueOf(2000000)),JsonMapper.builder().build());
		UUID user=UUID.randomUUID();String mark="contenido-privado-"+UUID.randomUUID();
		assertThat(usage.available()).isTrue();
		assertThat(usage.call(user,AiFunction.EXAM_GRADING,"test-model",mark,()->mark)).isEqualTo(mark);
		assertThatThrownBy(()->usage.call(null,AiFunction.EXAM_GENERATION,"test-model",mark,()->{throw new IllegalStateException(mark);})).isInstanceOf(IllegalStateException.class);
		assertThat(events).hasSize(2);
		assertThat(events.getFirst().outcome()).isEqualTo(AiUsageOutcome.SUCCESS);
		assertThat(events.getFirst().estimatedCostMicros()).isPositive();
		assertThat(events.getLast().outcome()).isEqualTo(AiUsageOutcome.FAILED_PLATFORM);
		assertThat(events.getLast().userId()).isNull();
		assertThat(events.toString()).doesNotContain(mark);
		when(quota.monthlySpendReached()).thenReturn(true);assertThat(usage.available()).isFalse();
		when(quota.monthlySpendReached()).thenThrow(new IllegalStateException("database-unavailable"));assertThat(usage.available()).isFalse();
		verify(quota,never()).check(any(),any());
	}
}
