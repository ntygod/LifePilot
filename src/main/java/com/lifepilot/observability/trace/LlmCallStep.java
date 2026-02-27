package com.lifepilot.observability.trace;

import java.time.Duration;
import java.time.Instant;

import jakarta.annotation.Nullable;

/**
 * LLM 调用步骤 — 记录一次 LLM 调用的完整信息，对齐 OpenTelemetry GenAI 语义约定。
 *
 * @param stepIndex    步骤序号
 * @param timestamp    发生时间
 * @param duration     耗时
 * @param providerId   LLM 提供商 ID
 * @param modelId      模型 ID
 * @param scene        调用场景
 * @param inputTokens  输入 Token 数
 * @param outputTokens 输出 Token 数
 * @param latency      LLM 响应延迟
 * @param cacheHit     是否命中缓存
 * @param temperature  采样温度
 * @param finishReason 完成原因（可为 null）
 * @author zsg
 * @since 2026-02-27
 */
public record LlmCallStep(
        int stepIndex,
        Instant timestamp,
        Duration duration,
        String providerId,
        String modelId,
        String scene,
        int inputTokens,
        int outputTokens,
        Duration latency,
        boolean cacheHit,
        double temperature,
        @Nullable String finishReason
) implements TraceStep {

    @Override
    public String typeName() {
        return "llm_call";
    }
}
