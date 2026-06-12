package com.lifepilot.memory.governance.lifecycle;

/**
 * 实体生命周期状态（7 态）。
 *
 * @author zsg
 * @since 2026-04-23
 */
public enum LifecycleState {
    ACTIVE,
    COMPLETED,
    CANCELLED,
    EXPIRED,
    SUPERSEDED,
    REGENERATION_NEEDED,
    /**
     * 新事实暗示此实体可能已过时，等待用户/LLM 确认。介于 ACTIVE 与 ARCHIVED 之间：
     * 仍可被召回，但 {@link com.lifepilot.memory.retrieval.HybridRetriever} 会显著降权
     * 并在 scoreBreakdown 填 {@code stalenessPenalty}。由 StalenessCoordinator 自动转入。
     */
    STALE_CANDIDATE,
    ARCHIVED;

    /** 合法状态转换检查；非法转换由调用方抛 IllegalStateException。 */
    public boolean canTransitionTo(LifecycleState next) {
        return switch (this) {
            case ACTIVE -> next != ACTIVE;
            case COMPLETED -> next == ARCHIVED;
            case REGENERATION_NEEDED -> next == SUPERSEDED || next == ARCHIVED;
            case STALE_CANDIDATE -> next == ACTIVE || next == SUPERSEDED || next == ARCHIVED;
            case CANCELLED, EXPIRED, SUPERSEDED -> next == ARCHIVED;
            case ARCHIVED -> false;
        };
    }

    /** 是否仍可被默认检索召回（ACTIVE / COMPLETED / REGENERATION_NEEDED / STALE_CANDIDATE）。 */
    public boolean isRetrievable() {
        return this == ACTIVE || this == COMPLETED
                || this == REGENERATION_NEEDED || this == STALE_CANDIDATE;
    }

    /**
     * 召回类查询（图遍历 / REM 端点存活校验等）使用的生命周期 SQL IN 子句 —— 单一来源，
     * 供 SemanticMemory / GraphReasoner 等复用，避免各处重复硬编码。
     *
     * <p>注意：此集合刻意不含 STALE_CANDIDATE（老化候选不作为图起点/端点），
     * 与 {@link #isRetrievable()} 的更宽集合区分。</p>
     */
    public static String recallableSqlInClause() {
        return "('ACTIVE', 'COMPLETED', 'REGENERATION_NEEDED')";
    }
}
