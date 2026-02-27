package com.lifepilot.observability.trace;

import java.time.Duration;
import java.time.Instant;

/**
 * 追踪步骤 sealed interface — 定义 Agent 执行过程中的五种步骤类型。
 *
 * <p>通过 sealed interface + record 实现编译时穷举检查，
 * 确保每种步骤类型都被正确处理。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public sealed interface TraceStep
        permits LlmCallStep, ToolCallStep, GuardrailStep,
                StateTransitionStep, EvaluationStep {

    /**
     * 步骤在 Trace 中的序号（从 0 开始）。
     */
    int stepIndex();

    /**
     * 步骤发生的时间戳。
     */
    Instant timestamp();

    /**
     * 步骤耗时。
     */
    Duration duration();

    /**
     * 步骤类型名称，用于序列化/反序列化时的类型标识。
     */
    String typeName();
}
