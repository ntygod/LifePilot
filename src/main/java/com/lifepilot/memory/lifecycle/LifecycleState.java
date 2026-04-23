package com.lifepilot.memory.lifecycle;

import java.util.EnumSet;
import java.util.Set;

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
    ARCHIVED;

    /** 非 ACTIVE / REGENERATION_NEEDED 的终态集合。 */
    private static final Set<LifecycleState> TERMINAL = EnumSet.of(CANCELLED, EXPIRED, SUPERSEDED, ARCHIVED);

    /** 合法状态转换检查；非法转换由调用方抛 IllegalStateException。 */
    public boolean canTransitionTo(LifecycleState next) {
        return switch (this) {
            case ACTIVE -> next != ACTIVE;
            case COMPLETED -> next == ARCHIVED;
            case REGENERATION_NEEDED -> next == SUPERSEDED || next == ARCHIVED;
            case CANCELLED, EXPIRED, SUPERSEDED -> next == ARCHIVED;
            case ARCHIVED -> false;
        };
    }

    /** 是否仍可被默认检索召回（ACTIVE / COMPLETED / REGENERATION_NEEDED）。 */
    public boolean isRetrievable() {
        return this == ACTIVE || this == COMPLETED || this == REGENERATION_NEEDED;
    }
}
