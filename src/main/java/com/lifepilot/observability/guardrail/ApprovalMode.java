package com.lifepilot.observability.guardrail;

/**
 * 审批模式枚举。
 *
 * <p>定义护栏检查结果对应的审批流程，与 {@link RiskLevel} 一一映射。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public enum ApprovalMode {

    /** 自动执行，无需审批。对应 {@link RiskLevel#LOW}。 */
    AUTO,

    /** 自动执行 + 审计日志。对应 {@link RiskLevel#MEDIUM}。 */
    AUTO_WITH_AUDIT,

    /** 需要用户确认后执行。对应 {@link RiskLevel#HIGH}。 */
    USER_CONFIRM,

    /** 用户确认 + 二次验证。对应 {@link RiskLevel#CRITICAL}。 */
    USER_CONFIRM_WITH_VERIFICATION
}
