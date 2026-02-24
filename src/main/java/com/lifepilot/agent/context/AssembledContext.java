package com.lifepilot.agent.context;

import java.util.List;

/**
 * 组装完成的上下文快照。
 *
 * @author zsg
 * @since 2026-07-20
 */
public record AssembledContext(
        String systemPrompt,
        String userPrompt,
        List<String> retrievedMemories,
        TokenBudget tokenBudget
) {
    /** 紧凑构造器 — 防御性拷贝。 */
    public AssembledContext {
        retrievedMemories = List.copyOf(retrievedMemories);
    }

    /** 返回 tokenBudget.totalConsumed()。 */
    public int totalTokens() {
        return tokenBudget.totalConsumed();
    }
}
