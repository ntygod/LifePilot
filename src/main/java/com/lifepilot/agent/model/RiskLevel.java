package com.lifepilot.agent.model;

/**
 * 风险等级枚举。
 *
 * @author zsg
 * @since 2026-07-20
 * @deprecated 请使用 {@link com.lifepilot.observability.guardrail.RiskLevel}
 */
@Deprecated(forRemoval = true)
public enum RiskLevel {

    /** 低风险 — 自动执行 */
    LOW,

    /** 中风险 — 自动执行 + 审计 */
    MEDIUM,

    /** 高风险 — 需用户确认 */
    HIGH,

    /** 极高风险 — 确认 + 二次验证 */
    CRITICAL
}
