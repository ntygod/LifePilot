package com.lifepilot.memory.working;

import java.util.List;

/**
 * 工作记忆槽位淘汰策略。
 *
 * <p>用于在 Token 超出预算时，从给定会话的槽位列表中选择一个待淘汰的槽位。</p>
 *
 * @author zsg
 * @since 2026-03-02
 */
public interface SlotEvictionPolicy {

    /**
     * 选择一个待淘汰的槽位。
     *
     * @param sessionId        会话 ID
     * @param slots            当前会话的所有槽位（可能为同步列表，调用方负责并发控制）
     * @param currentTokenUsage 当前会话已使用的 Token 数
     * @param tokenBudget      当前会话的 Token 预算上限
     * @return 待淘汰的槽位；若无法选择（例如所有槽位均被保护），返回 {@code null}
     */
    WorkingMemorySlot selectEvictionCandidate(String sessionId,
                                              List<WorkingMemorySlot> slots,
                                              int currentTokenUsage,
                                              int tokenBudget);
}

