package com.lifepilot.agent.context;

import com.lifepilot.agent.model.AgentPhase;
import com.lifepilot.memory.retrieval.RetrievalWeights;

/**
 * 默认记忆检索策略 — 按 AgentPhase 差异化配置。
 *
 * <p>各阶段策略：
 * <ul>
 *   <li>UNDERSTANDING: topK=10, 高向量权重(0.50), 广泛语义检索</li>
 *   <li>PLANNING: topK=5, 高图遍历权重(0.40), 获取实体关系</li>
 *   <li>EXECUTING: topK=3, 低检索预算, 更多空间给工具 Schema</li>
 *   <li>REFLECTING: topK=8, 高 FTS 权重(0.45), 检索历史执行经验</li>
 *   <li>RESPONDING: topK=5, 平衡三路权重, 生成连贯回复</li>
 *   <li>TERMINATED: 跳过检索</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public class DefaultMemoryRetrievalStrategy implements MemoryRetrievalStrategy {

    @Override
    public RetrievalStrategyConfig getStrategy(AgentPhase phase) {
        return switch (phase) {
            case UNDERSTANDING -> new RetrievalStrategyConfig(
                    10,
                    new RetrievalWeights(0.50f, 0.30f, 0.20f, 0.05f, 0.1f, 60),
                    true, false);
            case PLANNING -> new RetrievalStrategyConfig(
                    5,
                    new RetrievalWeights(0.30f, 0.30f, 0.40f, 0.05f, 0.1f, 60),
                    true, false);
            case EXECUTING -> new RetrievalStrategyConfig(
                    3,
                    new RetrievalWeights(0.45f, 0.30f, 0.25f, 0.05f, 0.1f, 60),
                    false, false);
            case REFLECTING -> new RetrievalStrategyConfig(
                    8,
                    new RetrievalWeights(0.25f, 0.45f, 0.30f, 0.05f, 0.1f, 60),
                    true, false);
            case RESPONDING -> new RetrievalStrategyConfig(
                    5,
                    new RetrievalWeights(0.35f, 0.35f, 0.30f, 0.05f, 0.1f, 60),
                    true, false);
            case TERMINATED -> RetrievalStrategyConfig.SKIP;
        };
    }
}
