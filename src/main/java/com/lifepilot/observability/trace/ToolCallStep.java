package com.lifepilot.observability.trace;

import java.time.Duration;
import java.time.Instant;

import com.lifepilot.observability.guardrail.RiskLevel;
import jakarta.annotation.Nullable;

/**
 * 工具调用步骤 — 记录一次工具调用的完整信息。
 *
 * @param stepIndex    步骤序号
 * @param timestamp    发生时间
 * @param duration     耗时
 * @param toolId       工具 ID
 * @param toolAction   工具动作
 * @param inputJson    输入 JSON（可为 null）
 * @param outputJson   输出 JSON（可为 null）
 * @param success      是否成功
 * @param errorMessage 错误信息（可为 null）
 * @param riskLevel    风险等级
 * @author zsg
 * @since 2026-02-27
 */
public record ToolCallStep(
        int stepIndex,
        Instant timestamp,
        Duration duration,
        String toolId,
        String toolAction,
        @Nullable String inputJson,
        @Nullable String outputJson,
        boolean success,
        @Nullable String errorMessage,
        RiskLevel riskLevel
) implements TraceStep {

    @Override
    public String typeName() {
        return "tool_call";
    }
}
