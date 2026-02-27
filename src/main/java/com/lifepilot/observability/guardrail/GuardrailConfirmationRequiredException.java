package com.lifepilot.observability.guardrail;

/**
 * 护栏确认异常 — 当护栏策略判定操作需要用户确认时抛出。
 *
 * @author zsg
 * @since 2026-02-27
 */
public class GuardrailConfirmationRequiredException extends RuntimeException {

    private final String policyId;
    private final String message;
    private final ApprovalMode approvalMode;

    public GuardrailConfirmationRequiredException(String policyId, String message, ApprovalMode approvalMode) {
        super("护栏需要确认: policyId=" + policyId + ", message=" + message);
        this.policyId = policyId;
        this.message = message;
        this.approvalMode = approvalMode;
    }

    public String getPolicyId() { return policyId; }
    public String getConfirmationMessage() { return message; }
    public ApprovalMode getApprovalMode() { return approvalMode; }
}
