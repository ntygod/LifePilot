package com.lifepilot.memory.quality;

/**
 * 记忆可信等级。
 *
 * @author zsg
 * @since 2026-05-05
 */
public enum MemoryTrustLevel {
    VERIFIED,
    EXPLICIT,
    INFERRED,
    DERIVED,
    UNVERIFIED;

    /**
     * 是否允许进入 prompt 注入候选集。
     */
    public boolean isPromptInjectable() {
        return this != UNVERIFIED;
    }

    /**
     * 是否允许直接派生 L4 程序记忆。
     */
    public boolean canPromoteToProcedural() {
        return this == VERIFIED || this == EXPLICIT;
    }
}
