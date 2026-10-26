package com.talenti.exam.config;

import java.io.IOException;
import java.util.concurrent.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ResourceLoader;
import org.springframework.scheduling.annotation.EnableScheduling;
import com.talenti.exam.adapter.*;
import com.talenti.exam.port.*;
import tools.jackson.databind.json.JsonMapper;
import com.talenti.exam.service.ExamNotice;

@Configuration(proxyBeanMethods=false)
@EnableConfigurationProperties({ExamProperties.class,ExamAiProperties.class,ExamOpenAiAccess.class})
@EnableScheduling
public class ExamConfig {
	@Bean(name="examExecutor",destroyMethod="shutdownNow")
	ExecutorService examExecutor() { return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("exam-",0).factory()); }
	@Bean ExamBank examBank(ResourceLoader loader,JsonMapper json) throws IOException {
		try(var in=loader.getResource("classpath:exam/bank-v1.json").getInputStream()) { return new ExamBank(json.readTree(in),json); }
	}
	@Bean ExamNotice examNotice(ResourceLoader loader,ExamProperties p) throws IOException {
		try(var in=loader.getResource("classpath:exam/notice-"+p.noticeVersion()+".md").getInputStream()) {
			return new ExamNotice(p.noticeVersion(),new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)
					.replace("60 minutos",p.timeLimit().toMinutes()+" minutos"));
		}
	}
	@Bean ExamAuthoringPort examAuthor(ExamAiProperties ai,ExamOpenAiAccess access,ExamProperties p,ExamBank bank,JsonMapper json) {
		return ai.enabled()?new OpenAiExam(ai.model(),ExamAiTransport.http(access.baseUrl(),access.apiKey()),json,p.gradingTimeout()):new SimulatedExamAuthoring(bank);
	}
	@Bean OpenAnswerGraderPort examOpenGrader(ExamAiProperties ai,ExamOpenAiAccess access,ExamProperties p,JsonMapper json) {
		return ai.enabled()?new OpenAiExam(ai.model(),ExamAiTransport.http(access.baseUrl(),access.apiKey()),json,p.gradingTimeout()):new SimulatedOpenAnswerGrader();
	}
	@Bean CodeExecutionPort examExecution(ExamProperties properties,JsonMapper json) {
		if(properties.runner().mode().equals("simulated")) return new SimulatedCodeExecution();
		if(!properties.runner().mode().equals("volume")) throw new IllegalArgumentException("Modo de ejecución del examen inválido");
		var programs=new VolumeCodeExecution(properties.runner(),json);
		var sql=new SqlCodeExecution(properties.sql(),json);
		return (language,code,cases,limits)->language==com.talenti.exam.domain.ExamQuestion.Language.SQL
				?sql.run(language,code,cases,limits):programs.run(language,code,cases,limits);
	}
}
