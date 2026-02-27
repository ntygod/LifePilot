package com.lifepilot.observability.trace;

import jakarta.annotation.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 追踪上下文 — 可变工作对象，在 Trace 生命周期内累积步骤数据。
 *
 * <p>通过 {@link TraceContextPropagator} 绑定到当前线程/虚拟线程，
 * 在 {@link TraceRecorder#endTrace} 时构建不可变的 {@link TraceRecord}。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class TraceContext {

    private final String traceId;
    private final String sessionId;
    private final String goal;
    private final Instant startTime;
    private final List<TraceStep> steps = new ArrayList<>();
    private @Nullable TraceMetadata metadata;
    private @Nullable String finalOutput;
    private boolean success;
    private @Nullable String errorMessage;
    private @Nullable String terminationReason;
    private @Nullable Instant endTime;
    private int totalInputTokens;
    private int totalOutputTokens;

    public TraceContext(String traceId, String sessionId, String goal) {
        this.traceId = traceId;
        this.sessionId = sessionId;
        this.goal = goal;
        this.startTime = Instant.now();
    }

    public TraceContext(String traceId, String sessionId, String goal, TraceMetadata metadata) {
        this(traceId, sessionId, goal);
        this.metadata = metadata;
    }

    // ─── 访问器 ───

    public String traceId() { return traceId; }
    public String sessionId() { return sessionId; }
    public String goal() { return goal; }
    public Instant startTime() { return startTime; }
    public List<TraceStep> steps() { return steps; }
    public @Nullable TraceMetadata metadata() { return metadata; }
    public @Nullable String finalOutput() { return finalOutput; }
    public boolean success() { return success; }
    public @Nullable String errorMessage() { return errorMessage; }
    public @Nullable String terminationReason() { return terminationReason; }
    public @Nullable Instant endTime() { return endTime; }
    public int totalInputTokens() { return totalInputTokens; }
    public int totalOutputTokens() { return totalOutputTokens; }

    // ─── 修改器 ───

    public void addStep(TraceStep step) {
        steps.add(step);
        // 累加 Token 统计
        if (step instanceof LlmCallStep llm) {
            totalInputTokens += llm.inputTokens();
            totalOutputTokens += llm.outputTokens();
        }
    }

    public void setFinalOutput(@Nullable String finalOutput) { this.finalOutput = finalOutput; }
    public void setSuccess(boolean success) { this.success = success; }
    public void setErrorMessage(@Nullable String errorMessage) { this.errorMessage = errorMessage; }
    public void setTerminationReason(@Nullable String terminationReason) { this.terminationReason = terminationReason; }
    public void setEndTime(@Nullable Instant endTime) { this.endTime = endTime; }
}
