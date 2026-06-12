package com.lifepilot.agent.initiative.execute;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * 主动执行授权 — 用户可通过对话自然授权。
 *
 * <p>授权按行为模式粒度管理，渐进信任：
 * 第一次以对话形式询问 → 用户说"下次直接做" → 记录授权 → 后续直接执行</p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public record ExecutionPermission(
    String id,
    /** 行为模式标识，如 "daily_summary" / "weekly_report" */
    String actionPattern,
    /** 人类可读描述："每天早上整理今日待办" */
    String description,
    /** 允许使用的工具白名单 */
    List<String> allowedTools,
    /** 允许的最高风险级别（最高 MEDIUM） */
    RiskLevel maxRisk,
    boolean active,
    Instant grantedAt,
    @Nullable Instant revokedAt
) {
    public boolean isValid() {
        return active && revokedAt == null;
    }

    public enum RiskLevel {
        /** 只读操作 */
        LOW,
        /** 可写但可逆 */
        MEDIUM,
        /** 不可逆（永远需要对话确认） */
        HIGH,
        /** 涉及外部通信/金钱（永远需要对话确认） */
        CRITICAL;

        public boolean allowsAutoExecution() {
            return this == LOW || this == MEDIUM;
        }
    }
}
