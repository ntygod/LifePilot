package com.lifepilot.agent.context;

import com.lifepilot.memory.retrieval.RetrievalWeights;

/**
 * 默认记忆检索策略 — 广泛语义检索。
 *
 * <p>ReAct 架构下使用统一策略：topK=10, 高向量权重(0.50), 广泛语义检索。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class DefaultMemoryRetrievalStrategy implements MemoryRetrievalStrategy {

    @Override
    public RetrievalStrategyConfig getDefaultStrategy() {
        return new RetrievalStrategyConfig(
                10,
                new RetrievalWeights(0.50f, 0.30f, 0.20f, 0.05f, 0.1f, 60),
                true, false);
    }
}
