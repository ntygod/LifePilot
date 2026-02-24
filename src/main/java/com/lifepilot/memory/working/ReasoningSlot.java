package com.lifepilot.memory.working;

import java.time.Instant;

/**
 * 推理槽位 — 存储中间推理状态（检索上下文、思考链）。
 *
 * @author zsg
 * @since 2026-02-24
 */
public record ReasoningSlot(
        String thought,
        String source,
        int tokenCount,
        float importance,
        Instant createdAt
) implements WorkingMemorySlot {

    /** 创建检索上下文槽位（importance=0.3）。 */
    public static ReasoningSlot retrievalContext(String context, int tokenCount) {
        return new ReasoningSlot(context, "hybrid-retrieval", tokenCount, 0.3f, Instant.now());
    }

    /** 创建思考链槽位（importance=0.4）。 */
    public static ReasoningSlot chainOfThought(String thought, int tokenCount) {
        return new ReasoningSlot(thought, "chain-of-thought", tokenCount, 0.4f, Instant.now());
    }
}
