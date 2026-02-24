package com.lifepilot.agent.context;

import com.lifepilot.memory.retrieval.RetrievalWeights;

/**
 * 检索策略配置 — 单个阶段的检索参数。
 *
 * @param topK         返回前 K 个结果
 * @param weights      三路检索权重
 * @param graphEnabled 是否启用图遍历
 * @param skip         是否跳过检索（TERMINATED 阶段）
 *
 * @author zsg
 * @since 2026-02-25
 */
public record RetrievalStrategyConfig(
        int topK,
        RetrievalWeights weights,
        boolean graphEnabled,
        boolean skip
) {
    /** TERMINATED 阶段使用的跳过配置。 */
    public static final RetrievalStrategyConfig SKIP =
            new RetrievalStrategyConfig(0, RetrievalWeights.DEFAULT, false, true);
}
