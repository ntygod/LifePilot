package com.lifepilot.observability.guardrail;

/**
 * 护栏检查结果 sealed interface — 三种可能的检查结果。
 *
 * <p>使用 sealed interface + 内部 record 实现编译时穷举检查。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public sealed interface GuardrailResult
        permits GuardrailResult.Passed, GuardrailResult.Blocked,
                GuardrailResult.NeedsConfirmation {

    /**
     * 检查通过。
     *
     * @param policyId 策略 ID
     */
    record Passed(String policyId) implements GuardrailResult {
    }

    /**
     * 检查被阻断。
     *
     * @param policyId  策略 ID
     * @param reason    阻断原因
     * @param riskLevel 风险等级
     */
    record Blocked(String policyId, String reason, RiskLevel riskLevel) implements GuardrailResult {
    }

    /**
     * 需要用户确认。
     *
     * @param policyId     策略 ID
     * @param message      确认消息
     * @param approvalMode 审批模式
     */
    record NeedsConfirmation(String policyId, String message,
                             ApprovalMode approvalMode) implements GuardrailResult {
    }
}
