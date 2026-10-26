package com.talenti.exam.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import com.talenti.exam.config.ExamAiProperties;
import com.talenti.shared.ai.*;
import tools.jackson.databind.json.JsonMapper;

/** Solo tamaños, función y costo; el usuario nunca se añade a la petición del proveedor. */
@Component
public class ExamUsage {
	private final AiUsageQuota quota;
	private final AiUsageRecorder recorder;
	private final ExamAiProperties ai;
	private final JsonMapper json;
	public ExamUsage(AiUsageQuota quota,AiUsageRecorder recorder,ExamAiProperties ai,JsonMapper json) {
		this.quota=quota; this.recorder=recorder; this.ai=ai; this.json=json;
	}
	public boolean available() { try { return !quota.monthlySpendReached(); } catch(RuntimeException e) { return false; } }
	public <T> T call(UUID user,AiFunction function,String model,Object input,Supplier<T> call) {
		int in=json.writeValueAsString(input).length(),out=0; boolean success=false;
		try { T result=call.get(); out=json.writeValueAsString(result).length(); success=true; return result; }
		finally {
			long cost=0;
			if(ai.enabled()&&success) cost=ai.inputPerMillionTokens().multiply(BigDecimal.valueOf((in+3L)/4))
					.add(ai.outputPerMillionTokens().multiply(BigDecimal.valueOf((out+3L)/4))).setScale(0,RoundingMode.CEILING).longValue();
			try { recorder.record(new AiUsageEvent(UUID.randomUUID(),user,function,ai.enabled()?AiLevel.ENHANCED:AiLevel.BASIC,
					ai.enabled()?"openai":"simulated",model,success?AiUsageOutcome.SUCCESS:AiUsageOutcome.FAILED_PLATFORM,in,out,cost)); }
			catch(RuntimeException ignored) { /* La medición no convierte una respuesta en un fallo del estudiante. */ }
		}
	}
}
