package com.lifepilot.agent.context;

/**
 * 记忆检索策略 — 决定检索行为。
 *
 * @author zsg
 * @since 2026-02-25
 */
public interface MemoryRetrievalStrategy {

    /**
     * 获取默认检索配置（ReAct 架构使用，无阶段区分）。
     *
     * <p>使用广泛语义检索策略（topK=10）。</p>
     *
     * @return 默认检索配置
     */
    RetrievalStrategyConfig getDefaultStrategy();
}
