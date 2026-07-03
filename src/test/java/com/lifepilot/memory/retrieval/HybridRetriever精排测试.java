package com.lifepilot.memory.retrieval;

import com.lifepilot.interaction.web.repository.MemoryProvenanceRepository;
import com.lifepilot.knowledge.rerank.RerankCandidate;
import com.lifepilot.memory.retrieval.config.MemoryRetrievalProperties;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.memory.store.procedural.IntentMatcher;
import com.lifepilot.memory.store.scope.MemoryReadFilter;
import com.lifepilot.rerank.router.RerankRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HybridRetriever 精排测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
class HybridRetriever精排测试 {

    private VectorSearcher vectorSearcher;
    private FtsSearcher ftsSearcher;
    private GraphTraverser graphTraverser;
    private SemanticMemory semanticMemory;
    private JdbcTemplate jdbcTemplate;
    private MemoryRetrievalProperties properties;
    private MemoryProvenanceRepository provenanceRepository;

    @BeforeEach
    void setUp() {
        vectorSearcher = mock(VectorSearcher.class);
        ftsSearcher = mock(FtsSearcher.class);
        graphTraverser = mock(GraphTraverser.class);
        semanticMemory = mock(SemanticMemory.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        provenanceRepository = mock(MemoryProvenanceRepository.class);
        properties = new MemoryRetrievalProperties();
        properties.setMinVectorSimilarity(0.0f);
        properties.setMinFusedScore(0.0f);
        when(jdbcTemplate.update(anyString(), org.mockito.ArgumentMatchers.<Object[]>any())).thenReturn(1);
        when(provenanceRepository.findStaleEntityIds(anyCollection())).thenReturn(Set.of());
    }

    @Test
    void query为空时检索失败() {
        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                mock(RerankRouter.class), provenanceRepository);

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> retriever.retrieve("   ", 10, RetrievalWeights.DEFAULT));

        assertTrue(exception.getMessage().contains("混合检索 query 不能为空"));
    }

    @Test
    void topK非法时检索失败() {
        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                mock(RerankRouter.class), provenanceRepository);

        var exception = assertThrows(
                IllegalArgumentException.class,
                () -> retriever.retrieve("test query", 0, RetrievalWeights.DEFAULT));

        assertTrue(exception.getMessage().contains("混合检索 topK 必须大于 0"));
    }

    @Test
    void weights为空时检索失败() {
        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                mock(RerankRouter.class), provenanceRepository);

        var exception = assertThrows(
                NullPointerException.class,
                () -> retriever.retrieve("test query", 10, null));

        assertTrue(exception.getMessage().contains("检索权重不能为空"));
    }

    @Test
    void 读取过滤可用实体ID返回null时检索失败() {
        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                mock(RerankRouter.class), provenanceRepository);
        when(semanticMemory.findEligibleEntityIds(MemoryReadFilter.userMemory())).thenReturn(null);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> retriever.retrieve("test query", 10, RetrievalWeights.DEFAULT, MemoryReadFilter.userMemory()));

        assertTrue(exception.getMessage().contains("读取过滤可用实体 ID返回 null"));
        verify(vectorSearcher, never()).searchEntities(anyString(), anyInt(), anyFloat(), any());
    }

    @Test
    void 记忆精排启用且路由可用时执行精排() {
        properties.getReranker().setEnabled(true);
        var rerankRouter = mock(RerankRouter.class);
        when(rerankRouter.isMemoryRerankEnabled()).thenReturn(true);
        when(rerankRouter.memoryTopK()).thenReturn(10);

        setupMockResults();
        when(rerankRouter.rerankMemoryCandidates(anyString(), anyCollectionToList()))
                .thenAnswer(invocation -> {
                    List<RerankCandidate> candidates = new ArrayList<>(invocation.getArgument(1));
                    List<RerankCandidate> reranked = new ArrayList<>();
                    for (int i = candidates.size() - 1; i >= 0; i--) {
                        RerankCandidate candidate = candidates.get(i);
                        reranked.add(new RerankCandidate(candidate.id(), candidate.content(), 0.9 - i * 0.1));
                    }
                    return reranked;
                });

        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                rerankRouter, provenanceRepository);

        var results = retriever.retrieve("test query", 10, RetrievalWeights.DEFAULT);

        verify(rerankRouter).rerankMemoryCandidates(anyString(), anyCollectionToList());
        assertFalse(results.isEmpty(), "精排后应有结果");
    }

    @Test
    void 记忆精排返回未知候选ID时检索失败() {
        properties.getReranker().setEnabled(true);
        var rerankRouter = mock(RerankRouter.class);
        when(rerankRouter.isMemoryRerankEnabled()).thenReturn(true);
        when(rerankRouter.rerankMemoryCandidates(anyString(), anyCollectionToList()))
                .thenReturn(List.of(new RerankCandidate("entity-unknown", "未知候选", 1.0)));

        setupMockResults();

        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                rerankRouter, provenanceRepository);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> retriever.retrieve("test query", 10, RetrievalWeights.DEFAULT));

        assertTrue(exception.getMessage().contains("记忆精排失败"));
        assertTrue(exception.getCause().getMessage().contains("未知候选 ID"));
    }

    @Test
    void 总开关关闭时不执行记忆精排() {
        properties.getReranker().setEnabled(false);
        var rerankRouter = mock(RerankRouter.class);
        when(rerankRouter.isMemoryRerankEnabled()).thenReturn(true);
        when(rerankRouter.memoryTopK()).thenReturn(10);

        setupMockResults();

        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                rerankRouter, provenanceRepository);

        var results = retriever.retrieve("test query", 10, RetrievalWeights.DEFAULT);

        verify(rerankRouter, never()).rerankMemoryCandidates(anyString(), anyCollectionToList());
        assertFalse(results.isEmpty(), "禁用精排后仍应有结果");
    }

    @Test
    void 路由不存在时构造失败() {
        properties.getReranker().setEnabled(true);
        setupMockResults();

        var exception = assertThrows(
                NullPointerException.class,
                () -> new HybridRetriever(
                        vectorSearcher, ftsSearcher, graphTraverser,
                        semanticMemory, null, properties, jdbcTemplate,
                        null, provenanceRepository));

        assertTrue(exception.getMessage().contains("rerankRouter"));
    }

    @Test
    void L4意图匹配失败时检索失败() {
        setupMockResults();
        var intentMatcher = mock(IntentMatcher.class);
        when(intentMatcher.match(anyString()))
                .thenThrow(new IllegalStateException("L4 索引缺失"));

        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, intentMatcher, properties, jdbcTemplate,
                mock(RerankRouter.class), provenanceRepository);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> retriever.retrieve("test query", 10, RetrievalWeights.DEFAULT));

        assertTrue(exception.getMessage().contains("L4 意图匹配"));
        assertTrue(exception.getCause().getMessage().contains("L4 索引缺失"));
    }

    @Test
    void fusedScore阈值会过滤低分结果() {
        properties.setMinFusedScore(0.5f);
        setupMockResults();

        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                mock(RerankRouter.class), provenanceRepository);

        var results = retriever.retrieve("test query", 10, RetrievalWeights.DEFAULT);

        for (var result : results) {
            assertTrue(result.fusedScore() >= 0.5f,
                    "fusedScore %.4f 低于阈值 0.5".formatted(result.fusedScore()));
        }
    }

    @Test
    void entityId去重后仅保留最高分结果() {
        String duplicateId = "entity-dup";
        var vecResults = List.of(
                new VectorSearchResult(duplicateId, 0.9f),
                new VectorSearchResult("entity-unique", 0.8f)
        );
        var ftsResults = List.of(
                new RankedItem(duplicateId, "TOPIC", "dup", "desc", 0.85f,
                        Instant.now(), 0.5f, null, Instant.now())
        );
        var graphResults = List.of(
                new RankedItem(duplicateId, "TOPIC", "dup", "desc", 0.7f,
                        Instant.now(), 0.5f, null, Instant.now())
        );

        var entities = new HashMap<String, TemporalEntity>();
        entities.put(duplicateId, buildEntity(duplicateId));
        entities.put("entity-unique", buildEntity("entity-unique"));

        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat(), any())).thenReturn(vecResults);
        when(ftsSearcher.search(anyString(), anyInt())).thenReturn(ftsResults);
        when(graphTraverser.traverse(anyString(), anyInt(), isNull())).thenReturn(graphResults);
        when(semanticMemory.findByIds(anyCollection())).thenReturn(entities);

        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                mock(RerankRouter.class), provenanceRepository);

        var results = retriever.retrieve("test query", 10, RetrievalWeights.DEFAULT);
        var entityIds = results.stream().map(RetrievalResult::entityId).toList();

        assertEquals(new HashSet<>(entityIds).size(), entityIds.size(), "结果中存在重复 entityId");
        assertEquals(1, entityIds.stream().filter(id -> id.equals(duplicateId)).count());
    }

    @Test
    void provenance待复核查询返回null时检索失败() {
        setupMockResults();
        when(provenanceRepository.findStaleEntityIds(anyCollection())).thenReturn(null);

        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                mock(RerankRouter.class), provenanceRepository);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> retriever.retrieve("test query", 10, RetrievalWeights.DEFAULT));

        assertTrue(exception.getMessage().contains("provenance 待复核查询返回 null"));
    }

    @Test
    void 可信度实体批量查询缺候选时检索失败() {
        var ftsResults = List.of(
                new RankedItem("entity-trust-missing", "TOPIC", "命中项", "描述",
                        0.8f, Instant.now(), 0.5f, null, Instant.now())
        );
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat(), any())).thenReturn(List.of());
        when(ftsSearcher.search(anyString(), anyInt())).thenReturn(ftsResults);
        when(graphTraverser.traverse(anyString(), anyInt(), isNull())).thenReturn(List.of());
        when(semanticMemory.findByIds(anyCollection()))
                .thenReturn(Map.of("entity-trust-missing", buildEntity("entity-trust-missing")),
                        Map.of());

        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                mock(RerankRouter.class), provenanceRepository);

        var exception = assertThrows(
                IllegalStateException.class,
                () -> retriever.retrieve("test query", 10, RetrievalWeights.DEFAULT));

        assertTrue(exception.getMessage().contains("可信度实体批量查询 缺少候选实体"));
    }

    @Test
    void 精确文本命中应压过无关高向量结果() {
        var exactId = "entity-exact";
        var vectorId = "entity-vector";
        var vecResults = List.of(new VectorSearchResult(vectorId, 0.99f));
        var ftsResults = List.of(new RankedItem(
                exactId,
                "PREFERENCE",
                "MT-CANCEL-0507 不提醒下午5点检查记忆抽取日志",
                "用户明确要求取消 MT-CANCEL-0507。",
                4.8f,
                Instant.now(),
                0.2f,
                null,
                Instant.now()));

        var entities = new HashMap<String, TemporalEntity>();
        entities.put(exactId, buildEntity(exactId));
        entities.put(vectorId, buildEntity(vectorId));

        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat(), any())).thenReturn(vecResults);
        when(ftsSearcher.search(anyString(), anyInt())).thenReturn(ftsResults);
        when(graphTraverser.traverse(anyString(), anyInt(), isNull())).thenReturn(List.of());
        when(semanticMemory.findByIds(anyCollection())).thenReturn(entities);

        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                mock(RerankRouter.class), provenanceRepository);

        var results = retriever.retrieve("取消 MT-CANCEL-0507", 10, RetrievalWeights.DEFAULT);

        assertFalse(results.isEmpty());
        assertEquals(exactId, results.getFirst().entityId());
    }

    private void setupMockResults() {
        var vecResults = List.of(
                new VectorSearchResult("entity-1", 0.9f),
                new VectorSearchResult("entity-2", 0.7f)
        );
        var ftsResults = List.of(
                new RankedItem("entity-1", "TOPIC", "e1", "desc1", 0.8f,
                        Instant.now(), 0.5f, null, Instant.now())
        );
        var graphResults = List.<RankedItem>of();

        var entities = new HashMap<String, TemporalEntity>();
        entities.put("entity-1", buildEntity("entity-1"));
        entities.put("entity-2", buildEntity("entity-2"));

        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat(), any())).thenReturn(vecResults);
        when(ftsSearcher.search(anyString(), anyInt())).thenReturn(ftsResults);
        when(graphTraverser.traverse(anyString(), anyInt(), isNull())).thenReturn(graphResults);
        when(semanticMemory.findByIds(anyCollection())).thenReturn(entities);
    }

    private TemporalEntity buildEntity(String id) {
        return new TemporalEntity(
                id, EntityType.TOPIC, id, "desc-" + id,
                Map.of(), 1, true, Instant.now(), null,
                null, 0.9f, 0.5f, 0, Instant.now(),
                Instant.now(), Instant.now()
        ,
                com.lifepilot.memory.governance.lifecycle.LifecycleState.ACTIVE,
                null,
                null,
                com.lifepilot.memory.governance.lifecycle.Temporality.PERSISTENT,
                null,
                false,
                java.util.List.of(),
                com.lifepilot.memory.consumption.quality.MemoryEvidenceKind.USER_CONFIRMED,
                com.lifepilot.memory.consumption.quality.MemoryTrustLevel.EXPLICIT,
                1.0f,
                1,
                Instant.now());
    }

    @SuppressWarnings("unchecked")
    private List<RerankCandidate> anyCollectionToList() {
        return (List<RerankCandidate>) org.mockito.ArgumentMatchers.any(List.class);
    }
}
