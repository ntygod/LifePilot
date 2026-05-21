package com.lifepilot.memory.governance.security;

/**
 * 记忆注入检测原因 — 供 {@link InjectionDetectionResult} 结构化记录。
 *
 * @author zsg
 * @since 2026-05-09
 */
public enum InjectionReason {
    /** 未触发任何检测规则。 */
    CLEAN,

    /** 命中 prompt injection 文本模式。 */
    PROMPT_INJECTION_PATTERN,

    /** trustScore 在当前 space 分布中偏离阈值。 */
    TRUST_SCORE_OUTLIER,

    /** 语义异常（预留，本 spec 不实现）。 */
    SEMANTIC_OUTLIER,

    /** 命中人工维护的黑名单（预留）。 */
    MANUAL_BLACKLIST
}
