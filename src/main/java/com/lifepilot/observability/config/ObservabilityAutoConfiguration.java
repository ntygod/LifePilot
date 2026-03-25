package com.lifepilot.observability.config;

import com.lifepilot.agent.config.AgentAutoConfiguration;
import com.lifepilot.observability.evaluation.EvaluationCore;
import com.lifepilot.observability.evaluation.TrajectoryEvaluator;
import com.lifepilot.observability.guardrail.GuardrailAdvisor;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.observability.guardrail.GuardrailPolicy;
import com.lifepilot.observability.redactor.DataRedactor;
import com.lifepilot.observability.trace.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 可观测性模块 Spring Boot 自动配置。
 *
 * <p>通过 {@code lifepilot.observability} 下的子开关控制各组件注册：
 * <ul>
 *   <li>{@code trace.enabled} — Trace 追踪组件</li>
 *   <li>{@code guardrail.enabled} — 护栏引擎组件</li>
 *   <li>{@code evaluation.enabled} — 轨迹评估组件</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-27
 */
@AutoConfiguration(before = AgentAutoConfiguration.class)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityAutoConfiguration.class);

    // ─── 脱敏组件（无条件注册，其他组件依赖） ───

    @Bean
    @ConditionalOnMissingBean
    public DataRedactor dataRedactor() {
        log.info("可观测性: 注册 DataRedactor 脱敏引擎");
        return new DataRedactor();
    }

    // ─── Trace 追踪组件 ───

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public TraceStepSerializer traceStepSerializer() {
        return new TraceStepSerializer();
    }

    @Bean
    @ConditionalOnMissingBean
    public TraceContextPropagator traceContextPropagator(ObservabilityProperties properties) {
        return new TraceContextPropagator(properties.getTrace().isUseScopedValue());
    }

    @Bean
    @ConditionalOnMissingBean(TraceRecorder.class)
    @ConditionalOnProperty(prefix = "lifepilot.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public TraceRecorder traceRecorder(JdbcTemplate jdbcTemplate,
                                       TraceStepSerializer serializer,
                                       TraceContextPropagator propagator,
                                       DataRedactor redactor,
                                       ObservabilityProperties properties) {
        log.info("可观测性: 注册 TraceRecorderImpl 追踪记录器");
        return new TraceRecorderImpl(jdbcTemplate, serializer, propagator, redactor, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public TraceQuery traceQuery(JdbcTemplate jdbcTemplate,
                                  TraceStepSerializer serializer,
                                  ObservabilityProperties properties) {
        return new TraceQuery(jdbcTemplate, serializer, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.observability.trace", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public TraceAdvisor traceAdvisor(TraceRecorder traceRecorder) {
        log.info("可观测性: 注册 TraceAdvisor（Spring AI Advisor）");
        return new TraceAdvisor(traceRecorder);
    }

    // ─── 护栏组件 ───

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.observability.guardrail", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public GuardrailEngine guardrailEngine(JdbcTemplate jdbcTemplate,
                                            TraceContextPropagator propagator,
                                            ObservabilityProperties properties) {
        log.info("可观测性: 注册 GuardrailEngine 护栏引擎");
        return new GuardrailEngine(jdbcTemplate, propagator, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.observability.guardrail", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public GuardrailAdvisor guardrailAdvisor(GuardrailEngine guardrailEngine) {
        log.info("可观测性: 注册 GuardrailAdvisor（Spring AI Advisor）");
        return new GuardrailAdvisor(guardrailEngine);
    }

    @Bean
    @ConditionalOnProperty(prefix = "lifepilot.observability.guardrail", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public GuardrailPolicy defaultBudgetLimitPolicy(GuardrailEngine guardrailEngine,
                                                    ObservabilityProperties properties) {
        int dailyTokenLimit = properties.getGuardrail().getBudgetLimit().getDailyTokenLimit();
        var policy = GuardrailPolicy.budgetLimitPolicy("default-budget-limit", true, 20, dailyTokenLimit);
        guardrailEngine.registerPolicy(policy);
        log.info("可观测性: 注册默认 BudgetLimitPolicy: dailyTokenLimit={}", dailyTokenLimit);
        return policy;
    }

    /**
     * 将每日 Token 聚合器注册为 TraceRecorder 的 onTraceEnd 监听器，
     * 在每次追踪结束后累计当天 Token 消耗。
     */
    @Bean
    @ConditionalOnBean(TraceRecorder.class)
    @ConditionalOnProperty(prefix = "lifepilot.observability.guardrail", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AutoCloseable dailyTokenUsageListener(TraceRecorder traceRecorder, JdbcTemplate jdbcTemplate) {
        log.info("可观测性: 注册 DailyTokenUsage 为 TraceEnd 监听器");
        return traceRecorder.onTraceEnd(trace -> persistDailyTokenUsage(jdbcTemplate, trace));
    }

    // ─── 评估组件 ───

    @Bean
    @ConditionalOnMissingBean
    public EvaluationCore evaluationCore() {
        log.info("可观测性: 注册 EvaluationCore 共享五维评估核心");
        return new EvaluationCore();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "lifepilot.observability.evaluation", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public TrajectoryEvaluator trajectoryEvaluator(JdbcTemplate jdbcTemplate,
                                                    ObservabilityProperties properties,
                                                    EvaluationCore evaluationCore) {
        log.info("可观测性: 注册 TrajectoryEvaluator 轨迹评估引擎");
        return new TrajectoryEvaluator(jdbcTemplate, properties, evaluationCore);
    }

    /**
     * 将 TrajectoryEvaluator 注册为 TraceRecorder 的 onTraceEnd 监听器，
     * 使每次追踪结束后自动触发在线评估并持久化结果。
     */
    @Bean
    @ConditionalOnBean({TraceRecorder.class, TrajectoryEvaluator.class})
    public AutoCloseable trajectoryEvaluatorListener(TraceRecorder traceRecorder,
                                                      TrajectoryEvaluator evaluator) {
        log.info("可观测性: 注册 TrajectoryEvaluator 为 TraceEnd 监听器");
        return traceRecorder.onTraceEnd(trace -> {
            try {
                evaluator.evaluateOnline(trace);
            } catch (Exception e) {
                log.warn("轨迹在线评估失败: traceId={}, error={}", trace.traceId(), e.getMessage());
            }
        });
    }

    private static void persistDailyTokenUsage(JdbcTemplate jdbcTemplate, TraceRecord trace) {
        int totalTokens = trace.totalTokens();
        if (totalTokens <= 0) {
            return;
        }

        String usageDate = resolveUsageDate(trace).toString();
        String now = Instant.now().toString();
        jdbcTemplate.update("""
                INSERT INTO daily_token_usage (
                    usage_date, input_tokens, output_tokens, total_tokens, created_at, updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT(usage_date) DO UPDATE SET
                    input_tokens = daily_token_usage.input_tokens + excluded.input_tokens,
                    output_tokens = daily_token_usage.output_tokens + excluded.output_tokens,
                    total_tokens = daily_token_usage.total_tokens + excluded.total_tokens,
                    updated_at = excluded.updated_at
                """,
                usageDate,
                trace.inputTokens(),
                trace.outputTokens(),
                totalTokens,
                now,
                now
        );
    }

    private static LocalDate resolveUsageDate(TraceRecord trace) {
        var endTime = trace.endTime() != null ? trace.endTime() : trace.startTime();
        return LocalDate.ofInstant(endTime, ZoneId.systemDefault());
    }
}
