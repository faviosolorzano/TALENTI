package com.talenti.exam.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import com.talenti.exam.domain.ExamQuestion.Language;
import com.talenti.exam.domain.ExamScoring;
import com.talenti.exam.port.CodeExecutionPort;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("talenti.exam")
public record ExamProperties(@DefaultValue("true") boolean enabled, @DefaultValue("60m") Duration timeLimit,
		@DefaultValue("2") int visibleCases, @DefaultValue("3") int hiddenCases,
		@DefaultValue("2") int maxRegenerations, @DefaultValue("15") int maxRunsPerQuestion,
		@DefaultValue("30s") Duration gradingTimeout, @DefaultValue("24h") Duration gradingRetryWindow,
		@DefaultValue("1h") Duration runnerRetryWindow, @DefaultValue("v1") String noticeVersion,
		@DefaultValue Map<String, Language> languageMap, @DefaultValue Weights weights,
		@DefaultValue Scoring scoring, @DefaultValue Runner runner, @DefaultValue Sql sql) {
	public ExamProperties {
		if (languageMap == null || languageMap.isEmpty()) languageMap = Map.ofEntries(
				Map.entry("java",Language.JAVA),Map.entry("spring-boot",Language.JAVA),
				Map.entry("sql",Language.SQL),Map.entry("postgresql",Language.SQL),Map.entry("mysql",Language.SQL),
				Map.entry("javascript",Language.JAVASCRIPT),Map.entry("typescript",Language.JAVASCRIPT),
				Map.entry("node-js",Language.JAVASCRIPT),Map.entry("react",Language.JAVASCRIPT),Map.entry("vue",Language.JAVASCRIPT),Map.entry("python",Language.PYTHON));
		languageMap = Map.copyOf(languageMap);
		if (timeLimit.isNegative() || timeLimit.isZero() || visibleCases < 1 || hiddenCases < 1 || maxRegenerations < 0
				|| maxRunsPerQuestion < 1 || gradingTimeout.isNegative() || gradingTimeout.isZero()) throw new IllegalArgumentException("Configuración de examen inválida");
	}
	public record Weights(@DefaultValue("6.25") BigDecimal choice, @DefaultValue("10") BigDecimal open,
			@DefaultValue("15") BigDecimal code, @DefaultValue Map<String,BigDecimal> rubric) {
		public Weights {
			rubric = rubric == null ? Map.of() : Map.copyOf(rubric);
			if (choice.signum() <= 0 || open.signum() <= 0 || code.signum() <= 0
					|| choice.multiply(BigDecimal.valueOf(4)).add(open.multiply(BigDecimal.valueOf(3))).add(code.multiply(BigDecimal.valueOf(3))).compareTo(BigDecimal.valueOf(100)) != 0)
				throw new IllegalArgumentException("Los pesos del examen deben sumar 100");
		}
	}
	public record Scoring(@DefaultValue("0.90") double fullThreshold, @DefaultValue("0.30") double zeroThreshold,
			@DefaultValue("0.80") double nonRunningCap, @DefaultValue("0.20") double sqlColumnsWeight) {
		public ExamScoring.Rules rules() { return new ExamScoring.Rules(fullThreshold,zeroThreshold,nonRunningCap,sqlColumnsWeight); }
	}
	public record Runner(@DefaultValue("volume") String mode, @DefaultValue("/var/lib/talenti/exam-jobs") String jobs,
			@DefaultValue("3s") Duration caseTimeout, @DefaultValue("10s") Duration compileTimeout,
			@DefaultValue("256") int memoryMb, @DefaultValue("65536") int outputBytes, @DefaultValue("10000") int codeBytes,
			@DefaultValue("64") int processes, @DefaultValue("2") int maxParallel, @DefaultValue("15s") Duration queueTimeout) {
		public CodeExecutionPort.Limits limits() { return new CodeExecutionPort.Limits(caseTimeout,compileTimeout,memoryMb,outputBytes,codeBytes,processes); }
	}
	public record Sql(@DefaultValue("jdbc:postgresql://exam-sql:5432/exam") String url,
			@DefaultValue("exam_owner") String owner, @DefaultValue("") String ownerPassword,
			@DefaultValue("exam_reader") String reader, @DefaultValue("") String readerPassword,
			@DefaultValue("2s") Duration statementTimeout, @DefaultValue("1000") int maxRows) {
		@Override public String toString() { return "ExamSql[credentials hidden]"; }
	}
}
