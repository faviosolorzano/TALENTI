package com.talenti.exam.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("talenti.ai.exam")
public record ExamAiProperties(@DefaultValue("false") boolean enabled, @DefaultValue("") String model,
		@DefaultValue("0") BigDecimal inputPerMillionTokens, @DefaultValue("0") BigDecimal outputPerMillionTokens) {
	public ExamAiProperties {
		if (inputPerMillionTokens.signum()<0||outputPerMillionTokens.signum()<0) throw new IllegalArgumentException("Precios de examen inválidos");
		if(enabled && (model.isBlank() || inputPerMillionTokens.signum()==0 || outputPerMillionTokens.signum()==0))
			throw new IllegalStateException("Examen con IA requiere TALENTI_AI_EXAM_MODEL y precios positivos configurados para estimar el gasto");
	}
}
