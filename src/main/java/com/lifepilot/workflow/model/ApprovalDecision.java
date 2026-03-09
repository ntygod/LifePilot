package com.lifepilot.workflow.model;

import java.time.Instant;

import org.springframework.lang.Nullable;

/**
 * 审批决策 record。
 *
 * @param decision  决策结果
 * @param decidedBy 决策人
 * @param reason    决策原因（可空）
 * @param decidedAt 决策时间
 * @author zsg
 * @since 2026-03-09
 */
public record ApprovalDecision(
        Decision decision,
        String decidedBy,
        @Nullable String reason,
        Instant decidedAt
) {

    /** 审批决策枚举。 */
    public enum Decision { APPROVED, REJECTED }
}
