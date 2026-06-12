package com.lifepilot.memory.retrieval;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.memory.retrieval.config.MemoryRetrievalProperties;
import com.lifepilot.memory.store.entity.EntityType;
import com.lifepilot.memory.store.entity.SemanticMemory;
import com.lifepilot.memory.store.entity.TemporalEntity;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QueryRefiner、QueryRewriter 与 HybridRetriever 端到端集成测试。
 *
 * @author zsg
 * @since 2026-03-24
 */
@DisplayName("QueryRefiner 到 QueryRewriter 到 HybridRetriever 集成测试")
class MemoryRetrieval_QueryRewrite_集成测试 {

    private GenerationRouter generationRouter;
    private EmbeddingRouter embeddingRouter;
    private PromptRegistry promptRegistry;
    private MemoryRetrievalProperties properties;
    private QueryRefiner queryRefiner;
    private QueryRewriter queryRewriter;

    private VectorSearcher vectorSearcher;
    private FtsSearcher ftsSearcher;
    private GraphTraverser graphTraverser;
    private SemanticMemory semanticMemory;
    private JdbcTemplate jdbcTemplate;
    private HybridRetriever hybridRetriever;

    @BeforeEach
    void setUp() {
        generationRouter = mock(GenerationRouter.class);
        embeddingRouter = mock(EmbeddingRouter.class);
        promptRegistry = mock(PromptRegistry.class);
        properties = new MemoryRetrievalProperties();

        properties.setQueryRewriteMode("rewrite");
        properties.setMaxRewrites(2);
        properties.setRewriteTimeoutMs(5000);
        properties.setMinFusedScore(0.0f);
        properties.setMinVectorSimilarity(0.0f);

        queryRefiner = new QueryRefiner(properties);
        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");
        queryRewriter = new QueryRewriter(generationRouter, embeddingRouter, properties, promptRegistry);

        vectorSearcher = mock(VectorSearcher.class);
        ftsSearcher = mock(FtsSearcher.class);
        graphTraverser = mock(GraphTraverser.class);
        semanticMemory = mock(SemanticMemory.class);
        jdbcTemplate = mock(JdbcTemplate.class);

        hybridRetriever = new HybridRetriever(
                vectorSearcher,
                ftsSearcher,
                graphTraverser,
                semanticMemory,
                null,
                properties,
                jdbcTemplate,
                null, null);
    }

    @Test
    @DisplayName("rewrite 模式下可以完成查询精炼、改写与检索")
    void rewrite模式下可以完成查询精炼改写与检索() {
        String rawQuery = "嘿那个帮我查一下之前聊过的旅行计划";

        when(generationRouter.call(
                anyString(),
                anyString(),
                isNull(),
                isNull(),
                isNull(),
                eq(GenerationCapability.CHAT),
                any()))
                .thenReturn(new LlmResponse("[\"之前讨论的旅行计划\",\"旅行安排和目的地\"]", null, null, List.of(), Map.of(), 10, 20, null, 0, "generation-service", "rewrite-model", 100, false));

        var vectorResult = new VectorSearchResult("entity-travel-1", 0.85f);
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat(), any()))
                .thenReturn(List.of(vectorResult));

        Instant now = Instant.now();
        var entity = new TemporalEntity(
                "entity-travel-1",
                EntityType.EVENT,
                "旅行计划",
                "去年讨论的日本旅行计划",
                Map.of(),
                1,
                true,
                now,
                null,
                null,
                0.9f,
                0.8f,
                1,
                now,
                now,
                now);
        when(semanticMemory.findByIds(any())).thenReturn(Map.of("entity-travel-1", entity));
        when(ftsSearcher.search(anyString(), anyInt())).thenReturn(List.of());
        when(graphTraverser.traverse(anyString(), anyInt())).thenReturn(List.of());

        String refined = queryRefiner.refine(rawQuery);
        assertFalse(refined.isBlank());

        var rewriteResult = queryRewriter.rewrite(refined);
        assertEquals(refined, rewriteResult.primaryQuery());
        assertFalse(rewriteResult.rewrittenQueries().isEmpty());

        var results = hybridRetriever.retrieve(rewriteResult.primaryQuery(), 10, RetrievalWeights.DEFAULT);
        assertFalse(results.isEmpty());
        assertEquals("entity-travel-1", results.getFirst().entityId());
    }

    @Test
    @DisplayName("none 模式下 QueryRewriter 直接透传且不调用模型")
    void none模式下QueryRewriter直接透传且不调用模型() {
        properties.setQueryRewriteMode("none");
        queryRewriter = new QueryRewriter(generationRouter, embeddingRouter, properties, promptRegistry);

        String rawQuery = "帮我查一下之前的会议记录";
        String refined = queryRefiner.refine(rawQuery);

        var rewriteResult = queryRewriter.rewrite(refined);

        assertEquals(refined, rewriteResult.primaryQuery());
        assertTrue(rewriteResult.rewrittenQueries().isEmpty());
        assertTrue(rewriteResult.hydeEmbedding().isEmpty());
        verify(generationRouter, never()).call(anyString(), anyString(), any(), any(), any(), any(), any());
        verify(embeddingRouter, never()).embed(anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("rewrite 模式失败时会降级回原始查询继续检索")
    void rewrite模式失败时会降级回原始查询继续检索() {
        when(generationRouter.call(
                anyString(),
                anyString(),
                isNull(),
                isNull(),
                isNull(),
                eq(GenerationCapability.CHAT),
                any()))
                .thenThrow(new RuntimeException("生成模型不可用"));

        var vectorResult = new VectorSearchResult("entity-meeting-1", 0.75f);
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat(), any()))
                .thenReturn(List.of(vectorResult));

        Instant now = Instant.now();
        var entity = new TemporalEntity(
                "entity-meeting-1",
                EntityType.EVENT,
                "会议记录",
                "上周的项目会议",
                Map.of(),
                1,
                true,
                now,
                null,
                null,
                0.8f,
                0.7f,
                1,
                now,
                now,
                now);
        when(semanticMemory.findByIds(any())).thenReturn(Map.of("entity-meeting-1", entity));
        when(ftsSearcher.search(anyString(), anyInt())).thenReturn(List.of());
        when(graphTraverser.traverse(anyString(), anyInt())).thenReturn(List.of());

        String rawQuery = "帮我查一下之前的会议记录";
        String refined = queryRefiner.refine(rawQuery);

        var rewriteResult = queryRewriter.rewrite(refined);
        assertEquals(refined, rewriteResult.primaryQuery());
        assertTrue(rewriteResult.rewrittenQueries().isEmpty());

        var results = hybridRetriever.retrieve(rewriteResult.primaryQuery(), 10, RetrievalWeights.DEFAULT);
        assertFalse(results.isEmpty());
        assertEquals("entity-meeting-1", results.getFirst().entityId());
    }
}
