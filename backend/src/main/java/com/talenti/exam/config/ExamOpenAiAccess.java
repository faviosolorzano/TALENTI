package com.talenti.exam.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("talenti.ai.openai")
public record ExamOpenAiAccess(@DefaultValue("") String apiKey,@DefaultValue("https://api.openai.com/v1") String baseUrl) {
	@Override public String toString() { return "ExamOpenAiAccess[credentials hidden]"; }
}
