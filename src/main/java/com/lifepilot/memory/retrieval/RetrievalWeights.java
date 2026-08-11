package com.lifepilot.memory.retrieval;

/**
 * 检索权重配置 — 控制三路检索的融合权重和后处理参数。
 *
 * @author zsg
 * @since 2026-02-25
 */
public record RetrievalWeights(
        float vectorWeight,
        float ftsWeight,
        float graphWeight,
        float recencyDecay,
        float importanceBoost,
        int rrfK
) {
    /** 默认权重配置。 */
    public static final RetrievalWeights DEFAULT = new RetrievalWeights(
            0.45f, 0.30f, 0.25f, 0.05f, 0.1f, 60);

    /** compact constructor：验证三权重之和 ≈ 1.0（误差 ≤ 0.01）。 */
    public RetrievalWeights {
        nonNegativeFinite(vectorWeight, "向量检索权重");
        nonNegativeFinite(ftsWeight, "全文检索权重");
        nonNegativeFinite(graphWeight, "图遍历权重");
        nonNegativeFinite(recencyDecay, "时间衰减系数");
        nonNegativeFinite(importanceBoost, "重要度加成系数");
        if (rrfK <= 0) {
            throw new IllegalArgumentException("RRF K 必须大于 0: " + rrfK);
        }
        float sum = vectorWeight + ftsWeight + graphWeight;
        if (Math.abs(sum - 1.0f) > 0.01f) {
            throw new IllegalArgumentException(
                    "检索权重之和必须为 1.0，当前为 %.2f".formatted(sum));
        }
    }

    /**
     * 自适应权重调整：向量 Top-1 分数低于 0.5 时降低 vectorWeight 30%。
     *
     * @param topVectorScore 向量检索 Top-1 分数
     * @return 调整后的权重
     */
    public RetrievalWeights adaptForLowVectorConfidence(float topVectorScore) {
        if (topVectorScore >= 0.5f) return this;
        float reduction = vectorWeight * 0.3f;
        return new RetrievalWeights(
                vectorWeight - reduction,
                ftsWeight + reduction * 0.7f,
                graphWeight + reduction * 0.3f,
                recencyDecay, importanceBoost, rrfK);
    }

    private static void nonNegativeFinite(float value, String name) {
        if (!Float.isFinite(value) || value < 0.0f) {
            throw new IllegalArgumentException(name + "必须是非负有限数: " + value);
        }
    }
}
