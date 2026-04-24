package com.lifepilot.tool.tier1;

/**
 * Tier 1 晋升建议状态。
 *
 * @author zsg
 * @since 2026-04-23
 */
public enum Tier1AdvisoryStatus {
    /** 等待审批。 */
    PENDING,
    /** 已批准晋升。 */
    APPROVED,
    /** 已驳回。 */
    REJECTED
}
