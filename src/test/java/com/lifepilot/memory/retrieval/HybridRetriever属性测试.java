package com.lifepilot.memory.retrieval;

import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.rerank.router.RerankRouter;
import jakarta.annotation.Nullable;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * HybridRetriever 属性测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
class HybridRetriever属性测试 {

    @Property(tries = 100)
    void 检索结果中不存在重复entityId(@ForAll("queriesWithDuplicateResults") QueryWithMockResults input) {
        var retriever = buildRetriever(
                input.vectorResults,
                input.ftsResults,
                input.graphResults,
                input.entities,
                null,
                input.properties
        );

        var results = retriever.retrieve(input.query, 20, RetrievalWeights.DEFAULT);
        var entityIds = results.stream().map(RetrievalResult::entityId).toList();
        assertEquals(new HashSet<>(entityIds).size(), entityIds.size(),
                "检索结果中存在重复 entityId: " + entityIds);
    }

    @Property(tries = 100)
    void 所有返回结果分数都不低于阈值(@ForAll("queriesWithRandomThreshold") QueryWithMockResults input) {
        var retriever = buildRetriever(
                input.vectorResults,
                input.ftsResults,
                input.graphResults,
                input.entities,
                null,
                input.properties
        );

        float minFusedScore = input.properties.getRetrieval().getMinFusedScore();
        var results = retriever.retrieve(input.query, 20, RetrievalWeights.DEFAULT);
        for (var result : results) {
            assertTrue(result.fusedScore() >= minFusedScore,
                    "fusedScore %.6f 低于阈值 %.6f, entityId=%s"
                            .formatted(result.fusedScore(), minFusedScore, result.entityId()));
        }
    }

    @Provide
    Arbitrary<QueryWithMockResults> queriesWithDuplicateResults() {
        return Arbitraries.integers().between(3, 15).flatMap(entityCount -> {
            var entityIdsArb = Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(10)
                    .list().ofSize(entityCount)
                    .map(ids -> ids.stream().distinct().toList());

            return entityIdsArb.flatMap(entityIds -> {
                if (entityIds.isEmpty()) {
                    return Arbitraries.just(new QueryWithMockResults(
                            "test query", List.of(), List.of(), List.of(), Map.of(), new MemoryProperties()
                    ));
                }

                var vectorArb = generateVectorResults(entityIds);
                var ftsArb = generateRankedItems(entityIds);
                var graphArb = generateRankedItems(entityIds);
                var queryArb = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20);

                return Combinators.combine(queryArb, vectorArb, ftsArb, graphArb)
                        .as((query, vecResults, ftsResults, graphResults) -> {
                            var entities = buildEntityMap(entityIds);
                            var properties = new MemoryProperties();
                            properties.getRetrieval().setMinFusedScore(0.0f);
                            properties.getRetrieval().setMinVectorSimilarity(0.0f);
                            return new QueryWithMockResults(
                                    query, vecResults, ftsResults, graphResults, entities, properties
                            );
                        });
            });
        });
    }

    @Provide
    Arbitrary<QueryWithMockResults> queriesWithRandomThreshold() {
        return Arbitraries.integers().between(3, 12).flatMap(entityCount -> {
            var entityIdsArb = Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(10)
                    .list().ofSize(entityCount)
                    .map(ids -> ids.stream().distinct().toList());

            return entityIdsArb.flatMap(entityIds -> {
                if (entityIds.isEmpty()) {
                    return Arbitraries.just(new QueryWithMockResults(
                            "test query", List.of(), List.of(), List.of(), Map.of(), new MemoryProperties()
                    ));
                }

                var vectorArb = generateVectorResults(entityIds);
                var ftsArb = generateRankedItems(entityIds);
                var graphArb = generateRankedItems(entityIds);
                var queryArb = Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(20);
                var thresholdArb = Arbitraries.floats().between(0.0f, 0.5f);

                return Combinators.combine(queryArb, vectorArb, ftsArb, graphArb, thresholdArb)
                        .as((query, vecResults, ftsResults, graphResults, threshold) -> {
                            var entities = buildEntityMap(entityIds);
                            var properties = new MemoryProperties();
                            properties.getRetrieval().setMinFusedScore(threshold);
                            properties.getRetrieval().setMinVectorSimilarity(0.0f);
                            return new QueryWithMockResults(
                                    query, vecResults, ftsResults, graphResults, entities, properties
                            );
                        });
            });
        });
    }

    private Arbitrary<List<VectorSearchResult>> generateVectorResults(List<String> entityIds) {
        return Arbitraries.integers().between(0, Math.min(entityIds.size(), 10)).flatMap(count -> {
            if (count == 0 || entityIds.isEmpty()) {
                return Arbitraries.just(List.of());
            }
            return Arbitraries.of(entityIds)
                    .flatMap(id -> Arbitraries.floats().between(0.1f, 1.0f)
                            .map(similarity -> new VectorSearchResult(id, similarity)))
                    .list()
                    .ofSize(count);
        });
    }

    private Arbitrary<List<RankedItem>> generateRankedItems(List<String> entityIds) {
        return Arbitraries.integers().between(0, Math.min(entityIds.size(), 10)).flatMap(count -> {
            if (count == 0 || entityIds.isEmpty()) {
                return Arbitraries.just(List.of());
            }
            return Arbitraries.of(entityIds)
                    .flatMap(id -> Arbitraries.floats().between(0.1f, 1.0f)
                            .map(score -> new RankedItem(
                                    id, "TOPIC", id, "desc-" + id,
                                    score, Instant.now(), 0.5f, null, Instant.now()
                            )))
                    .list()
                    .ofSize(count);
        });
    }

    private Map<String, TemporalEntity> buildEntityMap(List<String> entityIds) {
        var map = new HashMap<String, TemporalEntity>();
        for (var id : entityIds) {
            map.put(id, new TemporalEntity(
                    id, EntityType.TOPIC, id, "desc-" + id,
                    Map.of(), 1, true, Instant.now(), null,
                    null, 0.9f, 0.5f, 0, Instant.now(),
                    Instant.now(), Instant.now()
            ));
        }
        return map;
    }

    private HybridRetriever buildRetriever(List<VectorSearchResult> vectorResults,
                                           List<RankedItem> ftsResults,
                                           List<RankedItem> graphResults,
                                           Map<String, TemporalEntity> entities,
                                           @Nullable RerankRouter rerankRouter,
                                           MemoryProperties properties) {
        var vectorSearcher = mock(VectorSearcher.class);
        var ftsSearcher = mock(FtsSearcher.class);
        var graphTraverser = mock(GraphTraverser.class);
        var semanticMemory = mock(SemanticMemory.class);
        var jdbcTemplate = mock(JdbcTemplate.class);

        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat())).thenReturn(vectorResults);
        when(ftsSearcher.search(anyString(), anyInt())).thenReturn(ftsResults);
        when(graphTraverser.traverse(anyString(), anyInt())).thenReturn(graphResults);
        when(semanticMemory.findByIds(anyCollection())).thenReturn(entities);
        when(jdbcTemplate.update(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(1);

        return new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                rerankRouter
        , null);
    }

    record QueryWithMockResults(
            String query,
            List<VectorSearchResult> vectorResults,
            List<RankedItem> ftsResults,
            List<RankedItem> graphResults,
            Map<String, TemporalEntity> entities,
            MemoryProperties properties
    ) {
    }
}
