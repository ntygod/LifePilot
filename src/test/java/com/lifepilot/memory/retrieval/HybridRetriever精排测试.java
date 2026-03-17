package com.lifepilot.memory.retrieval;

import com.lifepilot.knowledge.rerank.LlmReranker;
import com.lifepilot.knowledge.rerank.RerankCandidate;
import com.lifepilot.knowledge.rerank.RerankerConfigProvider;
import com.lifepilot.knowledge.config.KnowledgeBaseProperties;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.semantic.EntityType;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HybridRetriever 单元测试 — 覆盖精排启用/禁用、fusedScore 阈值过滤、entityId 去重。
 *
 * @author zsg
 * @since 2026-03-15
 */
class HybridRetriever精排测试 {

    private VectorSearcher vectorSearcher;
    private FtsSearcher ftsSearcher;
    private GraphTraverser graphTraverser;
    private SemanticMemory semanticMemory;
    private JdbcTemplate jdbcTemplate;
    private MemoryProperties properties;

    @BeforeEach
    void setUp() {
        vectorSearcher = mock(VectorSearcher.class);
        ftsSearcher = mock(FtsSearcher.class);
        graphTraverser = mock(GraphTraverser.class);
        semanticMemory = mock(SemanticMemory.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        properties = new MemoryProperties();
        properties.getRetrieval().setMinVectorSimilarity(0.0f);
        properties.getRetrieval().setMinFusedScore(0.0f);

        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
    }

    // ─────────────────────────────────────────────
    //  精排启用/禁用测试
    // ─────────────────────────────────────────────

    @Test
    void 精排_enabled_true_Reranker可用_执行精排() {
        // enabled=true（默认）+ Reranker 可用 → 精排执行
        properties.getReranker().setEnabled(true);
        var reranker = mock(LlmReranker.class);
        var configProvider = buildConfigProvider(true);

        setupMockResults();

        // Mock Reranker 返回精排结果
        when(reranker.rerankGeneric(anyString(), anyList(), anyInt()))
                .thenAnswer(inv -> {
                    List<RerankCandidate> candidates = inv.getArgument(1);
                    // 精排后反转顺序并赋予新分数
                    var reranked = new ArrayList<RerankCandidate>();
                    for (int i = candidates.size() - 1; i >= 0; i--) {
                        reranked.add(new RerankCandidate(
                                candidates.get(i).id(),
                                candidates.get(i).content(),
                                0.9 - i * 0.1));
                    }
                    return reranked;
                });

        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                reranker, configProvider);

        var results = retriever.retrieve("test query", 10, RetrievalWeights.DEFAULT);

        // 验证 Reranker 被调用
        verify(reranker).rerankGeneric(anyString(), anyList(), anyInt());
        assertFalse(results.isEmpty(), "精排后应有结果");
    }

    @Test
    void 精排_enabled_false_强制禁用() {
        // enabled=false → 强制禁用，即使 Reranker Bean 存在
        properties.getReranker().setEnabled(false);
        var reranker = mock(LlmReranker.class);
        var configProvider = buildConfigProvider(true);

        setupMockResults();

        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                reranker, configProvider);

        var results = retriever.retrieve("test query", 10, RetrievalWeights.DEFAULT);

        // 验证 Reranker 未被调用
        verify(reranker, never()).rerankGeneric(anyString(), anyList(), anyInt());
        assertFalse(results.isEmpty(), "禁用精排后仍应有结果");
    }

    @Test
    void 精排_Reranker为null_跳过精排() {
        // Reranker Bean 不存在 → 跳过精排
        properties.getReranker().setEnabled(true);

        setupMockResults();

        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                null, null);

        var results = retriever.retrieve("test query", 10, RetrievalWeights.DEFAULT);

        // 无 Reranker，结果仍正常返回
        assertFalse(results.isEmpty(), "无 Reranker 时仍应有结果");
    }

    // ─────────────────────────────────────────────
    //  fusedScore 阈值过滤测试
    // ─────────────────────────────────────────────

    @Test
    void fusedScore阈值过滤_低分结果被移除() {
        // 设置较高的 minFusedScore 阈值
        properties.getRetrieval().setMinFusedScore(0.5f);

        setupMockResults();

        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                null, null);

        var results = retriever.retrieve("test query", 10, RetrievalWeights.DEFAULT);

        // 验证所有结果的 fusedScore ≥ 0.5
        for (var result : results) {
            assertTrue(result.fusedScore() >= 0.5f,
                    "fusedScore %.4f 低于阈值 0.5".formatted(result.fusedScore()));
        }
    }

    // ─────────────────────────────────────────────
    //  entityId 去重测试
    // ─────────────────────────────────────────────

    @Test
    void entityId去重_保留最高fusedScore() {
        // 三路检索返回相同 entityId 的结果
        String duplicateId = "entity-dup";
        var vecResults = List.of(
                new VectorSearchResult(duplicateId, 0.9f),
                new VectorSearchResult("entity-unique", 0.8f));
        var ftsResults = List.of(
                new RankedItem(duplicateId, "TOPIC", "dup", "desc", 0.85f,
                        Instant.now(), 0.5f, null, Instant.now()));
        var graphResults = List.of(
                new RankedItem(duplicateId, "TOPIC", "dup", "desc", 0.7f,
                        Instant.now(), 0.5f, null, Instant.now()));

        var entities = new HashMap<String, TemporalEntity>();
        entities.put(duplicateId, buildEntity(duplicateId));
        entities.put("entity-unique", buildEntity("entity-unique"));

        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat())).thenReturn(vecResults);
        when(ftsSearcher.search(anyString(), anyInt())).thenReturn(ftsResults);
        when(graphTraverser.traverse(anyString(), anyInt())).thenReturn(graphResults);
        when(semanticMemory.findByIds(anyCollection())).thenReturn(entities);

        var retriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate,
                null, null);

        var results = retriever.retrieve("test query", 10, RetrievalWeights.DEFAULT);

        // 验证无重复 entityId
        var entityIds = results.stream().map(RetrievalResult::entityId).toList();
        assertEquals(new HashSet<>(entityIds).size(), entityIds.size(),
                "结果中存在重复 entityId");

        // duplicateId 应只出现一次
        long dupCount = entityIds.stream().filter(id -> id.equals(duplicateId)).count();
        assertEquals(1, dupCount, "重复 entityId 应只保留一条");
    }

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** 设置默认的 Mock 检索结果（两个不同实体）。 */
    private void setupMockResults() {
        var vecResults = List.of(
                new VectorSearchResult("entity-1", 0.9f),
                new VectorSearchResult("entity-2", 0.7f));
        var ftsResults = List.of(
                new RankedItem("entity-1", "TOPIC", "e1", "desc1", 0.8f,
                        Instant.now(), 0.5f, null, Instant.now()));
        var graphResults = List.<RankedItem>of();

        var entities = new HashMap<String, TemporalEntity>();
        entities.put("entity-1", buildEntity("entity-1"));
        entities.put("entity-2", buildEntity("entity-2"));

        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat())).thenReturn(vecResults);
        when(ftsSearcher.search(anyString(), anyInt())).thenReturn(ftsResults);
        when(graphTraverser.traverse(anyString(), anyInt())).thenReturn(graphResults);
        when(semanticMemory.findByIds(anyCollection())).thenReturn(entities);
    }

    /** 构建测试用 TemporalEntity。 */
    private TemporalEntity buildEntity(String id) {
        return new TemporalEntity(
                id, EntityType.TOPIC, id, "desc-" + id,
                Map.of(), 1, true, Instant.now(), null,
                null, 0.9f, 0.5f, 0, Instant.now(),
                Instant.now(), Instant.now());
    }

    /** 构建 RerankerConfigProvider Mock（全局 Reranker 启用）。 */
    private RerankerConfigProvider buildConfigProvider(boolean globalEnabled) {
        var configProvider = mock(RerankerConfigProvider.class);
        var globalConfig = new KnowledgeBaseProperties.Reranker(
                globalEnabled, "llm", "", 5, "pointwise", 20,
                "jina", "", "", 5000);
        when(configProvider.getConfig()).thenReturn(globalConfig);
        when(configProvider.isMemoryRerankEnabled(anyBoolean())).thenAnswer(inv -> inv.getArgument(0));
        when(configProvider.getMemoryRerankTopK(anyInt())).thenAnswer(inv -> inv.getArgument(0));
        return configProvider;
    }
}
