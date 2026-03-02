package com.lifepilot.observability.guardrail;

import lombok.Getter;

/**
 * 护栏阻断异常 — 当护栏策略判定操作被阻断时抛出。
 *
 * @author zsg
 * @since 2026-02-27
 */
@Getter
public class GuardrailBlockedException extends RuntimeException {

    private final String policyId;
    private final String reason;
    private final RiskLevel riskLevel;

    public GuardrailBlockedException(String policyId, String reason, RiskLevel riskLevel) {
        super("护栏阻断: policyId=" + policyId + ", reason=" + reason);
        this.policyId = policyId;
        this.reason = reason;
        this.riskLevel = riskLevel;
    }

}
