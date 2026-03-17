package com.lifepilot.memory.retrieval;

import com.lifepilot.knowledge.rerank.Reranker;
import com.lifepilot.knowledge.rerank.RerankerConfigProvider;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import jakarta.annotation.Nullable;
import net.jqwik.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HybridRetriever 属性测试 — 验证实体去重和 fusedScore 阈值过滤不变量。
 *
 * @author zsg
 * @since 2026-03-15
 */
class HybridRetriever属性测试 {

    // ─────────────────────────────────────────────
    //  Property P6 — HybridRetriever 实体去重
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 8.1</b>
     *
     * <p>对任意查询和检索结果，HybridRetriever.retrieve() 返回的结果中
     * 不存在重复的 entityId。</p>
     */
    @Property(tries = 100)
    void 检索结果中无重复entityId(
            @ForAll("queriesWithDuplicateResults") QueryWithMockResults input) {

        var retriever = buildRetriever(
                input.vectorResults, input.ftsResults, input.graphResults,
                input.entities, null, null, input.properties);

        var results = retriever.retrieve(input.query, 20, RetrievalWeights.DEFAULT);

        // 验证：所有 entityId 唯一
        var entityIds = results.stream()
                .map(RetrievalResult::entityId)
                .toList();
        var uniqueIds = new HashSet<>(entityIds);
        assertEquals(uniqueIds.size(), entityIds.size(),
                "检索结果中存在重复 entityId: " + entityIds);
    }

    // ─────────────────────────────────────────────
    //  Property P7 — HybridRetriever fusedScore 阈值过滤
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 6.2</b>
     *
     * <p>对任意查询和随机 minFusedScore 阈值，HybridRetriever.retrieve() 返回的
     * 所有结果的 fusedScore 均 ≥ minFusedScore。</p>
     */
    @Property(tries = 100)
    void 所有返回结果fusedScore不低于阈值(
            @ForAll("queriesWithRandomThreshold") QueryWithMockResults input) {

        var retriever = buildRetriever(
                input.vectorResults, input.ftsResults, input.graphResults,
                input.entities, null, null, input.properties);

        float minFusedScore = input.properties.getRetrieval().getMinFusedScore();
        var results = retriever.retrieve(input.query, 20, RetrievalWeights.DEFAULT);

        // 验证：所有返回结果的 fusedScore ≥ minFusedScore
        for (var result : results) {
            assertTrue(result.fusedScore() >= minFusedScore,
                    "fusedScore %.6f 低于阈值 %.6f, entityId=%s"
                            .formatted(result.fusedScore(), minFusedScore, result.entityId()));
        }
    }

    // ─────────────────────────────────────────────
    //  数据生成器
    // ─────────────────────────────────────────────

    /** 生成包含潜在重复 entityId 的查询和 Mock 检索结果。 */
    @Provide
    Arbitrary<QueryWithMockResults> queriesWithDuplicateResults() {
        return Arbitraries.integers().between(3, 15).flatMap(entityCount -> {
            // 生成 entityCount 个唯一实体 ID
            var entityIdsArb = Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(10)
                    .list().ofSize(entityCount).map(ids -> ids.stream().distinct().toList());

            return entityIdsArb.flatMap(entityIds -> {
                if (entityIds.isEmpty()) {
                    return Arbitraries.just(new QueryWithMockResults(
                            "test query", List.of(), List.of(), List.of(), Map.of(), new MemoryProperties()));
                }

                // 生成向量结果（可能包含重复 entityId）
                var vectorArb = generateVectorResults(entityIds);
                // 生成 FTS 结果（可能与向量结果有重叠 entityId）
                var ftsArb = generateRankedItems(entityIds);
                // 生成图遍历结果（可能与前两路有重叠 entityId）
                var graphArb = generateRankedItems(entityIds);
                // 生成查询
                var queryArb = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20);

                return Combinators.combine(queryArb, vectorArb, ftsArb, graphArb)
                        .as((query, vecResults, ftsResults, graphResults) -> {
                            // 构建实体映射
                            var entities = buildEntityMap(entityIds);
                            var properties = new MemoryProperties();
                            // 设置较低的 minFusedScore 以确保结果不被全部过滤
                            properties.getRetrieval().setMinFusedScore(0.0f);
                            properties.getRetrieval().setMinVectorSimilarity(0.0f);
                            return new QueryWithMockResults(
                                    query, vecResults, ftsResults, graphResults, entities, properties);
                        });
            });
        });
    }

    /** 生成随机查询 + 随机 minFusedScore 阈值的 Mock 检索结果。 */
    @Provide
    Arbitrary<QueryWithMockResults> queriesWithRandomThreshold() {
        return Arbitraries.integers().between(3, 12).flatMap(entityCount -> {
            var entityIdsArb = Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(10)
                    .list().ofSize(entityCount).map(ids -> ids.stream().distinct().toList());

            return entityIdsArb.flatMap(entityIds -> {
                if (entityIds.isEmpty()) {
                    return Arbitraries.just(new QueryWithMockResults(
                            "test query", List.of(), List.of(), List.of(), Map.of(), new MemoryProperties()));
                }

                var vectorArb = generateVectorResults(entityIds);
                var ftsArb = generateRankedItems(entityIds);
                var graphArb = generateRankedItems(entityIds);
                var queryArb = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20);
                // 随机阈值 [0.0, 0.5]
                var thresholdArb = Arbitraries.floats().between(0.0f, 0.5f);

                return Combinators.combine(queryArb, vectorArb, ftsArb, graphArb, thresholdArb)
                        .as((query, vecResults, ftsResults, graphResults, threshold) -> {
                            var entities = buildEntityMap(entityIds);
                            var properties = new MemoryProperties();
                            properties.getRetrieval().setMinFusedScore(threshold);
                            properties.getRetrieval().setMinVectorSimilarity(0.0f);
                            return new QueryWithMockResults(
                                    query, vecResults, ftsResults, graphResults, entities, properties);
                        });
            });
        });
    }

    /** 生成向量检索结果（从 entityIds 中随机选取，可能重复）。 */
    private Arbitrary<List<VectorSearchResult>> generateVectorResults(List<String> entityIds) {
        return Arbitraries.integers().between(0, Math.min(entityIds.size(), 10)).flatMap(count -> {
            if (count == 0 || entityIds.isEmpty()) return Arbitraries.just(List.of());
            return Arbitraries.of(entityIds)
                    .flatMap(id -> Arbitraries.floats().between(0.1f, 1.0f)
                            .map(sim -> new VectorSearchResult(id, sim)))
                    .list().ofSize(count);
        });
    }

    /** 生成 RankedItem 列表（从 entityIds 中随机选取，可能重复）。 */
    private Arbitrary<List<RankedItem>> generateRankedItems(List<String> entityIds) {
        return Arbitraries.integers().between(0, Math.min(entityIds.size(), 10)).flatMap(count -> {
            if (count == 0 || entityIds.isEmpty()) return Arbitraries.just(List.of());
            return Arbitraries.of(entityIds)
                    .flatMap(id -> Arbitraries.floats().between(0.1f, 1.0f)
                            .map(score -> new RankedItem(id, "TOPIC", id, "desc-" + id,
                                    score, Instant.now(), 0.5f, null, Instant.now())))
                    .list().ofSize(count);
        });
    }

    /** 根据 entityIds 构建 TemporalEntity 映射。 */
    private Map<String, TemporalEntity> buildEntityMap(List<String> entityIds) {
        var map = new HashMap<String, TemporalEntity>();
        for (var id : entityIds) {
            map.put(id, new TemporalEntity(
                    id, EntityType.TOPIC, id, "desc-" + id,
                    Map.of(), 1, true, Instant.now(), null,
                    null, 0.9f, 0.5f, 0, Instant.now(),
                    Instant.now(), Instant.now()));
        }
        return map;
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /**
     * 构建 HybridRetriever，Mock 所有外部依赖。
     */
    private HybridRetriever buildRetriever(
            List<VectorSearchResult> vectorResults,
            List<RankedItem> ftsResults,
            List<RankedItem> graphResults,
            Map<String, TemporalEntity> entities,
            @Nullable Reranker reranker,
            @Nullable RerankerConfigProvider rerankerConfigProvider,
            MemoryProperties properties) {

        var vectorSearcher = mock(VectorSearcher.class);
        var ftsSearcher = mock(FtsSearcher.class);
        var graphTraverser = mock(GraphTraverser.class);
        var semanticMemory = mock(SemanticMemory.class);
        var jdbcTemplate = mock(JdbcTemplate.class);

        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat()))
                .thenReturn(vectorResults);
        when(ftsSearcher.search(anyString(), anyInt()))
                .thenReturn(ftsResults);
        when(graphTraverser.traverse(anyString(), anyInt()))
                .thenReturn(graphResults);
        when(semanticMemory.findByIds(anyCollection()))
                .thenReturn(entities);
        // retrieval_event_log 写入不影响测试
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        return new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                reranker, rerankerConfigProvider);
    }

    /** 查询 + Mock 检索结果的组合数据。 */
    record QueryWithMockResults(
            String query,
            List<VectorSearchResult> vectorResults,
            List<RankedItem> ftsResults,
            List<RankedItem> graphResults,
            Map<String, TemporalEntity> entities,
            MemoryProperties properties
    ) {}
}
