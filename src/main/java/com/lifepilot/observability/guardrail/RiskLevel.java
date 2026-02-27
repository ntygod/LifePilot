package com.lifepilot.observability.guardrail;

/**
 * 统一风险等级枚举。
 *
 * <p>风险等级在工具注册时声明，在护栏引擎中强制执行。
 * 不同风险等级对应不同的审批模式。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public enum RiskLevel {

    /** 低风险：只读操作，无副作用。自动执行，无需审批。 */
    LOW,

    /** 中风险：有副作用但可撤销。自动执行 + 审计日志。 */
    MEDIUM,

    /** 高风险：不可逆操作。需要用户确认后执行。 */
    HIGH,

    /** 关键风险：高风险 + 涉及敏感数据或资金。用户确认 + 二次验证。 */
    CRITICAL;

    /**
     * 判断是否需要用户确认。
     *
     * @return HIGH 和 CRITICAL 需要用户确认
     */
    public boolean requiresConfirmation() {
        return this == HIGH || this == CRITICAL;
    }

    /**
     * 判断是否需要审计日志。
     *
     * @return MEDIUM 及以上需要审计日志
     */
    public boolean requiresAudit() {
        return this.ordinal() >= MEDIUM.ordinal();
    }

    /**
     * 判断是否需要二次验证。
     *
     * @return 仅 CRITICAL 需要二次验证
     */
    public boolean requiresSecondaryVerification() {
        return this == CRITICAL;
    }

    /**
     * 获取对应的审批模式。
     *
     * @return 风险等级对应的审批模式
     */
    public ApprovalMode toApprovalMode() {
        return switch (this) {
            case LOW -> ApprovalMode.AUTO;
            case MEDIUM -> ApprovalMode.AUTO_WITH_AUDIT;
            case HIGH -> ApprovalMode.USER_CONFIRM;
            case CRITICAL -> ApprovalMode.USER_CONFIRM_WITH_VERIFICATION;
        };
    }
}
