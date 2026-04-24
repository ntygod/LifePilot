package com.lifepilot.tool.tier1;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * Tier 1 晋升建议记录。
 *
 * @param id 数据库主键（新建时为 null）
 * @param toolId 候选工具 ID
 * @param advisedAt 建议生成时间
 * @param windowDays 观察窗口天数
 * @param coverageRatio 会话覆盖率（0-1）
 * @param status 状态
 * @param reviewedBy 审批人
 * @param reviewedAt 审批时间
 * @author zsg
 * @since 2026-04-23
 */
public record Tier1Advisory(
        @Nullable Long id,
        String toolId,
        Instant advisedAt,
        int windowDays,
        double coverageRatio,
        Tier1AdvisoryStatus status,
        @Nullable String reviewedBy,
        @Nullable Instant reviewedAt
) {}
