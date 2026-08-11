package com.lifepilot.memory.retrieval.orchestrator;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 检索结果包 — {@link RetrievalOrchestrator} 的返回类型，
 * 聚合所有参与检索的 source、总延迟、最终合并后的 items。
 *
 * @param query     原始查询
 * @param strategy  使用的 intent 策略（{@link RetrievalIntent#name()}）
 * @param items     融合排序后的 top-K 结果
 * @param latencyMs 总耗时（毫秒）
 * @param sources   参与检索的 source 名称集合
 * @param metadata  自由元数据
 * @author zsg
 * @since 2026-05-09
 */
public record EvidenceBundle(
        String query,
        String strategy,
        List<EvidenceItem> items,
        long latencyMs,
        Set<String> sources,
        Map<String, Object> metadata
) {

    public EvidenceBundle {
        Objects.requireNonNull(query, "query 不能为空");
        Objects.requireNonNull(strategy, "strategy 不能为空");
        Objects.requireNonNull(items, "items 不能为空");
        Objects.requireNonNull(sources, "sources 不能为空");
        Objects.requireNonNull(metadata, "metadata 不能为空");
        items = List.copyOf(items);
        sources = Set.copyOf(sources);
        metadata = Map.copyOf(metadata);
        if (latencyMs < 0L) {
            throw new IllegalArgumentException("检索耗时不能为负数: " + latencyMs);
        }
    }

    /** 空结果便利构造。 */
    public static EvidenceBundle empty(String query) {
        return new EvidenceBundle(query == null ? "" : query, "GENERAL", List.of(), 0L, Set.of(), Map.of());
    }
}
