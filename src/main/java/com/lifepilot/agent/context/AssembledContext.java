package com.lifepilot.agent.context;

import java.util.List;

/**
 * 增强版上下文快照 — 携带检索元数据。
 *
 * @param systemPrompt        System Prompt 文本
 * @param userPrompt          User Prompt 文本
 * @param retrievedMemories   格式化后的记忆检索结果
 * @param tokenBudget         Token 预算分配与消耗
 * @param retrievalCount      检索返回的结果总数（截断前）
 * @param topRetrievalScore   最高 fusedScore
 * @param workingMemoryTokens WorkingMemory 注入的 Token 总数
 * @param degraded            是否发生降级
 *
 * @author zsg
 * @since 2026-07-20
 */
public record AssembledContext(
        String systemPrompt,
        String userPrompt,
        List<String> retrievedMemories,
        TokenBudget tokenBudget,
        int retrievalCount,
        float topRetrievalScore,
        int workingMemoryTokens,
        boolean degraded
) {
    /** 紧凑构造器 — 防御性拷贝。 */
    public AssembledContext {
        retrievedMemories = List.copyOf(retrievedMemories);
    }

    /** 返回 tokenBudget.totalConsumed()。 */
    public int totalTokens() {
        return tokenBudget.totalConsumed();
    }

    /** 基于当前上下文，仅替换 systemPrompt，返回新实例。 */
    public AssembledContext withSystemPrompt(String newSystemPrompt) {
        return new AssembledContext(
                newSystemPrompt,
                userPrompt(),
                retrievedMemories(),
                tokenBudget(),
                retrievalCount(),
                topRetrievalScore(),
                workingMemoryTokens(),
                degraded()
        );
    }
}
