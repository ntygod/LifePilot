package com.lifepilot.observability.trace;

import java.time.Duration;
import java.time.Instant;

import com.lifepilot.observability.guardrail.ApprovalMode;
import com.lifepilot.observability.guardrail.RiskLevel;
import jakarta.annotation.Nullable;

/**
 * 护栏检查步骤 — 记录一次护栏策略检查的结果。
 *
 * @param stepIndex    步骤序号
 * @param timestamp    发生时间
 * @param duration     耗时
 * @param policyId     策略 ID
 * @param checkType    检查类型
 * @param passed       是否通过
 * @param reason       原因（可为 null）
 * @param riskLevel    风险等级
 * @param approvalMode 审批模式
 * @author zsg
 * @since 2026-02-27
 */
public record GuardrailStep(
        int stepIndex,
        Instant timestamp,
        Duration duration,
        String policyId,
        String checkType,
        boolean passed,
        @Nullable String reason,
        RiskLevel riskLevel,
        ApprovalMode approvalMode
) implements TraceStep {

    @Override
    public String typeName() {
        return "guardrail";
    }
}
