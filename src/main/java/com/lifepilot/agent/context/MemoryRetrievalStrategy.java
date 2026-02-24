package com.lifepilot.agent.context;

import com.lifepilot.agent.model.AgentPhase;

/**
 * 记忆检索策略 — 根据 AgentPhase 决定检索行为。
 *
 * @author zsg
 * @since 2026-02-25
 */
public interface MemoryRetrievalStrategy {

    /**
     * 获取指定阶段的检索配置。
     *
     * @param phase 当前 AgentPhase
     * @return 检索配置，TERMINATED 阶段返回 SKIP 配置
     */
    RetrievalStrategyConfig getStrategy(AgentPhase phase);
}
