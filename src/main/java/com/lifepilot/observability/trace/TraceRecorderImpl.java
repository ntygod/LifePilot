package com.lifepilot.observability.trace;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.redactor.DataRedactor;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 追踪记录器实现 — 负责 Trace 生命周期管理、脱敏和异步持久化。
 *
 * <p>核心职责：
 * <ul>
 *   <li>通过 {@link TraceContextPropagator} 管理上下文绑定</li>
 *   <li>通过 {@link DataRedactor} 对 LlmCallStep 和 ToolCallStep 中的敏感字段脱敏</li>
 *   <li>通过 Virtual Thread 异步写入 SQLite（traces + trace_steps 表）</li>
 *   <li>SQLite 写入失败不阻塞 Agent 主循环</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class TraceRecorderImpl implements TraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(TraceRecorderImpl.class);

    private final JdbcTemplate jdbcTemplate;
    private final TraceStepSerializer serializer;
    private final TraceContextPropagator propagator;
    private final DataRedactor redactor;
    private final ObservabilityProperties properties;
    private final List<Consumer<TraceStepEvent>> stepListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<TraceRecord>> traceEndListeners = new CopyOnWriteArrayList<>();

    public TraceRecorderImpl(JdbcTemplate jdbcTemplate,
                             TraceStepSerializer serializer,
                             TraceContextPropagator propagator,
                             DataRedactor redactor,
                             ObservabilityProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.serializer = serializer;
        this.propagator = propagator;
        this.redactor = redactor;
        this.properties = properties;
    }

    @Override
    public TraceContext startTrace(String traceId, String sessionId, String goal) {
        var context = new TraceContext(traceId, sessionId, goal);
        propagator.bind(context);
        log.debug("追踪开始: traceId={}, sessionId={}", traceId, sessionId);
        return context;
    }

    @Override
    public TraceContext startTrace(String traceId, String sessionId, String goal, TraceMetadata metadata) {
        var context = new TraceContext(traceId, sessionId, goal, metadata);
        propagator.bind(context);
        log.debug("追踪开始（带元数据）: traceId={}, sessionId={}", traceId, sessionId);
        return context;
    }

    @Override
    public void recordStep(TraceContext context, TraceStep step) {
        // 对敏感字段脱敏
        TraceStep redactedStep = properties.getRedaction().isRedactInTrace()
                ? redactStep(step)
                : step;
        context.addStep(redactedStep);

        // 触发回调
        TraceStepEvent event = new TraceStepEvent(context.traceId(), redactedStep);
        for (Consumer<TraceStepEvent> listener : stepListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.warn("步骤回调执行异常: traceId={}, error={}", context.traceId(), e.getMessage());
            }
        }

        log.debug("步骤记录: traceId={}, stepIndex={}, type={}",
                context.traceId(), step.stepIndex(), step.typeName());
    }

    @Override
    public TraceRecord endTrace(TraceContext context, @Nullable String finalOutput,
                                boolean success, @Nullable String errorMessage,
                                @Nullable String terminationReason) {
        Instant endTime = Instant.now();
        context.setEndTime(endTime);
        context.setFinalOutput(finalOutput);
        context.setSuccess(success);
        context.setErrorMessage(errorMessage);
        context.setTerminationReason(terminationReason);

        long durationMs = Duration.between(context.startTime(), endTime).toMillis();
        int totalTokens = context.totalInputTokens() + context.totalOutputTokens();

        var record = new TraceRecord(
                context.traceId(),
                context.sessionId(),
                context.goal(),
                context.startTime(),
                endTime,
                durationMs,
                context.steps().size(),
                totalTokens,
                context.totalInputTokens(),
                context.totalOutputTokens(),
                success,
                terminationReason,
                finalOutput,
                errorMessage,
                context.steps(),
                context.metadata()
        );

        // 解绑上下文
        propagator.unbind();

        // 异步持久化（Virtual Thread）
        Thread.startVirtualThread(() -> persistTrace(record));

        // Trace 结束回调（实时事件）
        for (Consumer<TraceRecord> listener : traceEndListeners) {
            try {
                listener.accept(record);
            } catch (Exception e) {
                log.warn("TraceEnd 回调执行异常: traceId={}, error={}", context.traceId(), e.getMessage());
            }
        }

        log.info("追踪结束: traceId={}, success={}, steps={}, tokens={}",
                context.traceId(), success, context.steps().size(), totalTokens);

        return record;
    }

    @Override
    public AutoCloseable onStep(Consumer<TraceStepEvent> listener) {
        stepListeners.add(listener);
        return () -> stepListeners.remove(listener);
    }

    @Override
    public AutoCloseable onTraceEnd(Consumer<TraceRecord> listener) {
        traceEndListeners.add(listener);
        return () -> traceEndListeners.remove(listener);
    }

    @Override
    public Optional<TraceContext> currentContext() {
        return propagator.current();
    }

    // ─── 脱敏逻辑 ───

    /**
     * 对 TraceStep 中的敏感字段进行脱敏。
     * 仅对 LlmCallStep（finishReason）和 ToolCallStep（inputJson、outputJson、errorMessage）脱敏。
     */
    private TraceStep redactStep(TraceStep step) {
        return switch (step) {
            case ToolCallStep tool -> new ToolCallStep(
                    tool.stepIndex(), tool.timestamp(), tool.duration(),
                    tool.toolId(), tool.toolAction(),
                    redactNullable(tool.inputJson()),
                    redactNullable(tool.outputJson()),
                    tool.success(),
                    redactNullable(tool.errorMessage()),
                    tool.riskLevel()
            );
            case LlmCallStep llm -> llm; // LlmCallStep 无用户数据字段，无需脱敏
            case GuardrailStep guardrail -> new GuardrailStep(
                    guardrail.stepIndex(), guardrail.timestamp(), guardrail.duration(),
                    guardrail.policyId(), guardrail.checkType(), guardrail.passed(),
                    redactNullable(guardrail.reason()),
                    guardrail.riskLevel(), guardrail.approvalMode()
            );
            case StateTransitionStep state -> new StateTransitionStep(
                    state.stepIndex(), state.timestamp(), state.duration(),
                    state.phaseBefore(), state.phaseAfter(),
                    state.actionType(),
                    redactor.redact(state.actionSummary())
            );
            case EvaluationStep eval -> eval; // 评估步骤无用户数据，无需脱敏
        };
    }

    /**
     * 对可空字符串脱敏。
     */
    private @Nullable String redactNullable(@Nullable String text) {
        return text != null ? redactor.redact(text) : null;
    }

    // ─── 持久化逻辑 ───

    /**
     * 将 TraceRecord 持久化到 SQLite（traces + trace_steps 表）。
     * 写入失败时记录 WARN 日志，不阻塞 Agent 主循环。
     */
    private void persistTrace(TraceRecord record) {
        try {
            boolean recordPrompts = properties.getTrace().isRecordPrompts();

            // 内容落盘策略：默认不落盘 prompt/output（goal/finalOutput/tool I/O 等）
            String persistedGoal = recordPrompts ? record.goal() : "[[content_not_persisted]]";
            String persistedFinalOutput = recordPrompts ? record.finalOutput() : null;

            // 序列化元数据
            String metadataJson = null;
            if (record.metadata() != null) {
                try {
                    metadataJson = serializer.serializeMetadata(record.metadata());
                } catch (Exception e) {
                    log.error("元数据序列化失败: traceId={}, error={}", record.traceId(), e.getMessage());
                    metadataJson = "{}";
                }
            }

            // 写入 traces 表
            jdbcTemplate.update(
                    """
                    INSERT INTO traces (trace_id, session_id, goal, start_time, end_time,
                        total_duration_ms, total_steps, total_tokens, input_tokens, output_tokens,
                        success, termination_reason, final_output, error_message, metadata_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    record.traceId(), record.sessionId(), persistedGoal,
                    record.startTime().toString(),
                    record.endTime() != null ? record.endTime().toString() : null,
                    record.totalDurationMs(), record.totalSteps(), record.totalTokens(),
                    record.inputTokens(), record.outputTokens(),
                    record.success() ? 1 : 0,
                    record.terminationReason(), persistedFinalOutput, record.errorMessage(),
                    metadataJson, Instant.now().toString()
            );

            // 写入 trace_steps 表
            for (TraceStep step : record.steps()) {
                String detailJson;
                try {
                    TraceStep persistedStep = recordPrompts ? step : stripContent(step);
                    detailJson = serializer.serialize(persistedStep);
                } catch (Exception e) {
                    log.error("步骤序列化失败: traceId={}, stepIndex={}, error={}",
                            record.traceId(), step.stepIndex(), e.getMessage());
                    detailJson = "{}";
                }

                jdbcTemplate.update(
                        """
                        INSERT INTO trace_steps (trace_id, step_index, step_type, timestamp,
                            duration_ms, detail_json, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                        record.traceId(), step.stepIndex(), step.typeName(),
                        step.timestamp().toString(), step.duration().toMillis(),
                        detailJson, Instant.now().toString()
                );
            }

            log.info("追踪持久化成功: traceId={}, steps={}", record.traceId(), record.totalSteps());
        } catch (Exception e) {
            log.warn("追踪持久化失败: traceId={}, error={}", record.traceId(), e.getMessage());
        }
    }

    /**
     * 默认不落盘内容时，对步骤中的潜在敏感字段进行清空（仅用于持久化）。
     */
    private TraceStep stripContent(TraceStep step) {
        return switch (step) {
            case ToolCallStep tool -> new ToolCallStep(
                    tool.stepIndex(), tool.timestamp(), tool.duration(),
                    tool.toolId(), tool.toolAction(),
                    null,
                    null,
                    tool.success(),
                    null,
                    tool.riskLevel()
            );
            case GuardrailStep guardrail -> new GuardrailStep(
                    guardrail.stepIndex(), guardrail.timestamp(), guardrail.duration(),
                    guardrail.policyId(), guardrail.checkType(), guardrail.passed(),
                    null,
                    guardrail.riskLevel(), guardrail.approvalMode()
            );
            case StateTransitionStep state -> new StateTransitionStep(
                    state.stepIndex(), state.timestamp(), state.duration(),
                    state.phaseBefore(), state.phaseAfter(),
                    state.actionType(),
                    null
            );
            default -> step;
        };
    }
}
