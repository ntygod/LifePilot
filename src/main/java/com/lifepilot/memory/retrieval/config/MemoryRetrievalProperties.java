package com.lifepilot.memory.retrieval.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 记忆检索层配置属性。
 *
 * <p>绑定 {@code lifepilot.memory.retrieval} 配置前缀。</p>
 *
 * @author zsg
 * @since 2026-06-01
 */
@Setter
@Getter
@ConfigurationProperties(prefix = "lifepilot.memory.retrieval")
public class MemoryRetrievalProperties {

    // ─── Retrieval 基础配置 ───

    /** RRF 融合分数最低阈值，低于此值的检索结果将被过滤。0.0 表示不过滤，默认 0.08。 */
    private float minFusedScore = 0.08f;

    /** 查询精炼后最大长度（字符数），超过则截断。 */
    private int queryMaxLength = 100;

    /** 查询最短长度（字符数），短于此值跳过精炼直接返回原文。 */
    private int queryMinLength = 10;

    /** 跨会话 BM25 搜索最低分数阈值，低于此值的结果被过滤。 */
    private float minCrossSessionBm25Score = 1.0f;

    /** 检索质量低分阈值，topScore 低于此值时触发预算缩减。 */
    private float lowQualityScoreThreshold = 0.3f;

    /** 低质量检索时知识实体预算缩减比例（0~1）。 */
    private float lowQualityBudgetRatio = 0.5f;

    /** 时间衰减率 — 每天衰减的比例（线性衰减）。 */
    private float timeDecayRate = 0.002f;

    /** 时间衰减因子最小值 — 防止老实体完全被忽略。 */
    private float minTimeDecayFactor = 0.5f;

    /** 跨会话消息语义相似度最低阈值 [0.0, 1.0]，默认 0.45。 */
    private float minCrossSessionSemanticScore = 0.45f;

    /** 查询改写模式：rewrite / hyde / none，默认 none。 */
    private String queryRewriteMode = "none";

    /** rewrite 模式最大改写变体数，默认 3。 */
    private int maxRewrites = 3;

    /** 查询改写 LLM 调用超时（毫秒），默认 5000。 */
    private int rewriteTimeoutMs = 5000;

    /** 向量检索最低相似度阈值 [0.0, 1.0]，默认 0.15。 */
    private float minVectorSimilarity = 0.15f;

    /** 话题切换检测余弦相似度阈值 [0.0, 1.0]，默认 0.3。 */
    private float topicSwitchThreshold = 0.3f;

    /** 检索排序中 trust_score 的加成权重，默认 0.08。 */
    private float trustScoreBoostWeight = 0.08f;

    /** REGENERATION_NEEDED 结果的生命周期扣分，默认 0.12。 */
    private float staleLifecyclePenalty = 0.12f;

    /** COMPLETED 历史结果的生命周期扣分，默认 0.02。 */
    private float historicalLifecyclePenalty = 0.02f;

    /** STALE_CANDIDATE 结果的检索惩罚比例（0~1），默认 0.35，得分乘 0.65。 */
    private float stalenessRetrievalPenalty = 0.35f;

    // ─── AgenticTool 配置 ───

    /** Agentic Tool 配置 — 控制记忆 tool 的默认检索参数。 */
    private AgenticTool agenticTool = new AgenticTool();

    // ─── Reranker 精排配置 ───

    /** 精排配置。 */
    private Reranker reranker = new Reranker();

    // ─── RetrievalOrchestrator 编排层配置 ───

    /** 检索编排层配置。 */
    private Orchestrator orchestrator = new Orchestrator();

    /**
     * 记忆精排配置 — 控制 HybridRetriever 中可选的 Reranker 精排步骤。
     */
    @Setter
    @Getter
    public static class Reranker {

        /** 记忆精排强制关闭开关，默认 true（Reranker 可用时自动启用；设为 false 强制禁用）。 */
        private boolean enabled = true;

        /** 精排返回数量，默认 10。 */
        private int topK = 10;
    }

    /**
     * 检索编排层配置 — 控制 {@code RetrievalOrchestrator} 的 topK 与开关。
     */
    @Setter
    @Getter
    public static class Orchestrator {

        /** 总开关，默认启用。 */
        private boolean enabled = true;

        /** 默认单次检索返回的结果上限。 */
        private int defaultTopK = 10;

        /** 每个 source 单独调用的 topK。 */
        private int perSourceTopK = 5;
    }

    /**
     * Agentic Tool 配置 — 控制记忆 tool 的默认检索参数。
     */
    @Setter
    @Getter
    public static class AgenticTool {

        /** 知识实体 / 跨会话检索默认返回数量。 */
        private int defaultTopK = 10;

        /** 知识库文档检索默认返回数量。 */
        private int docsDefaultTopK = 5;

        /** 是否允许跨执行上下文检索经验，默认 false。 */
        private boolean crossContextRetrieval = false;
    }
}
