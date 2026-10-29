package com.talenti.shared.ai;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Escribe y cuenta en {@code ai_usage_event}. Interno de shared: los módulos usan las dos interfaces públicas. */
@Component
@EnableConfigurationProperties(AiLimitsProperties.class)
class JdbcAiUsage implements AiUsageRecorder, AiUsageQuota {

	private final JdbcClient jdbc;

	private final Clock clock;

	private final AiLimitsProperties limits;

	JdbcAiUsage(JdbcClient jdbc, Clock clock, AiLimitsProperties limits) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.limits = limits;
	}

	@Override
	public boolean record(AiUsageEvent event) {
		return jdbc.sql("""
				INSERT INTO ai_usage_event (id, occurred_at, user_id, function, level, adapter, model, outcome,
				                            input_size, output_size, read_id, estimated_cost_micros, reverses_read_id)
				VALUES (:id, :occurredAt, :userId, :function, :level, :adapter, :model, :outcome,
				        :inputSize, :outputSize, :readId, :cost, :reversesReadId)
				ON CONFLICT DO NOTHING""")
				.param("id", UUID.randomUUID())
				.param("occurredAt", OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC))
				.param("userId", event.userId())
				.param("function", event.function().name())
				.param("level", event.level().name())
				.param("adapter", event.adapter())
				.param("model", event.model())
				.param("outcome", event.outcome().name())
				.param("inputSize", Math.max(0, event.inputSize()))
				.param("outputSize", Math.max(0, event.outputSize()))
				.param("readId", event.readId())
				.param("cost", event.estimatedCostMicros())
				.param("reversesReadId", event.reversesReadId())
				.update() == 1;
	}

	@Override
	public boolean exists(UUID readId) {
		return jdbc.sql("SELECT count(*) FROM ai_usage_event WHERE read_id = :readId").param("readId", readId)
				.query(Integer.class).single() > 0;
	}

	@Override
	public QuotaStatus check(UUID userId, AiFunction function) {
		ZonedDateTime now = clock.instant().atZone(limits.zone());
		Instant monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(limits.zone()).toInstant();
		Instant nextMonth = now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(limits.zone()).toInstant();
		Instant dayStart = now.toLocalDate().atStartOfDay(limits.zone()).toInstant();
		Instant nextDay = now.toLocalDate().plusDays(1).atStartOfDay(limits.zone()).toInstant();

		int used = successes(userId, function, monthStart, nextMonth);
		Integer personalLimit = limits.perUserMonthly().get(function.configKey());
		int limit = personalLimit == null ? -1 : personalLimit;
		if (personalLimit != null && used >= personalLimit) {
			return new QuotaStatus(QuotaStatus.Block.PERSONAL, used, limit, nextMonth);
		}
		if (successes(null, function, dayStart, nextDay) >= limits.globalDaily()
				|| successes(null, function, monthStart, nextMonth) >= limits.globalMonthly()) {
			return new QuotaStatus(QuotaStatus.Block.GLOBAL, used, limit, nextMonth);
		}
		return new QuotaStatus(null, used, limit, nextMonth);
	}

	@Override
	public boolean monthlySpendReached() {
		return monthSpentMicros() >= limits.monthlySpendMicros();
	}

	@Override
	public long monthlySpendRemainingMicros() {
		return Math.max(0, limits.monthlySpendMicros() - monthSpentMicros());
	}

	private long monthSpentMicros() {
		ZonedDateTime now = clock.instant().atZone(limits.zone());
		Instant monthStart = now.toLocalDate().withDayOfMonth(1).atStartOfDay(limits.zone()).toInstant();
		Instant nextMonth = now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(limits.zone()).toInstant();
		return jdbc.sql("""
				SELECT coalesce(sum(estimated_cost_micros), 0) FROM ai_usage_event
				WHERE estimated_cost_micros IS NOT NULL AND occurred_at >= :from AND occurred_at < :until""")
				.param("from", OffsetDateTime.ofInstant(monthStart, ZoneOffset.UTC))
				.param("until", OffsetDateTime.ofInstant(nextMonth, ZoneOffset.UTC))
				.query(Long.class).single();
	}

	/** Usos completados en la ventana; con {@code userId} nulo, de todas las personas. */
	private int successes(UUID userId, AiFunction function, Instant from, Instant until) {
		return jdbc.sql("""
				SELECT count(*) FROM ai_usage_event usage
				WHERE usage.function = :function AND usage.outcome = 'SUCCESS'
				  AND usage.occurred_at >= :from AND usage.occurred_at < :until
				  AND (CAST(:userId AS uuid) IS NULL OR usage.user_id = CAST(:userId AS uuid))
				  AND NOT EXISTS (SELECT 1 FROM ai_usage_event reversal
				      WHERE reversal.reverses_read_id = usage.read_id
				        AND reversal.function = usage.function AND reversal.outcome = 'FAILED_PLATFORM'
				        AND reversal.user_id IS NOT DISTINCT FROM usage.user_id)""")
				.param("function", function.name())
				.param("from", OffsetDateTime.ofInstant(from, ZoneOffset.UTC))
				.param("until", OffsetDateTime.ofInstant(until, ZoneOffset.UTC))
				.param("userId", userId)
				.query(Integer.class).single();
	}

}
