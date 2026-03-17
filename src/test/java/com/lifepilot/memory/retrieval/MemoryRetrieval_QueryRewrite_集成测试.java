package com.lifepilot.memory.retrieval;

import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.memory.semantic.SemanticMemory;
import com.lifepilot.memory.semantic.TemporalEntity;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * QueryRefiner → QueryRewriter → HybridRetriever 端到端集成测试。
 *
 * <p>验证完整的查询改写 + 检索流程：
 * raw query → QueryRefiner.refine() → QueryRewriter.rewrite() → HybridRetriever.retrieve()。
 * Mock LlmRouter 用于 rewrite 模式，Mock HybridRetriever 的底层依赖。</p>
 *
 * @author zsg
 * @since 2026-03-17
 */
@DisplayName("QueryRefiner → QueryRewriter → HybridRetriever 集成测试")
class MemoryRetrieval_QueryRewrite_集成测试 {

    private LlmRouter llmRouter;
    private PromptRegistry promptRegistry;
    private MemoryProperties properties;
    private QueryRefiner queryRefiner;
    private QueryRewriter queryRewriter;

    // HybridRetriever 依赖
    private VectorSearcher vectorSearcher;
    private FtsSearcher ftsSearcher;
    private GraphTraverser graphTraverser;
    private SemanticMemory semanticMemory;
    private JdbcTemplate jdbcTemplate;
    private HybridRetriever hybridRetriever;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        promptRegistry = mock(PromptRegistry.class);
        properties = new MemoryProperties();

        // 配置 rewrite 模式
        properties.getRetrieval().setQueryRewriteMode("rewrite");
        properties.getRetrieval().setMaxRewrites(2);
        properties.getRetrieval().setRewriteTimeoutMs(5000);
        // 降低阈值以便测试结果通过过滤
        properties.getRetrieval().setMinFusedScore(0.0f);
        properties.getRetrieval().setMinVectorSimilarity(0.0f);

        // 初始化真实的 QueryRefiner 和 QueryRewriter
        queryRefiner = new QueryRefiner(properties);
        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");
        queryRewriter = new QueryRewriter(llmRouter, properties, promptRegistry);

        // Mock HybridRetriever 底层依赖
        vectorSearcher = mock(VectorSearcher.class);
        ftsSearcher = mock(FtsSearcher.class);
        graphTraverser = mock(GraphTraverser.class);
        semanticMemory = mock(SemanticMemory.class);
        jdbcTemplate = mock(JdbcTemplate.class);

        hybridRetriever = new HybridRetriever(
                vectorSearcher, ftsSearcher, graphTraverser,
                semanticMemory, null, properties, jdbcTemplate, null, null);
    }

    @Test
    void 端到端_rawQuery经过精炼和改写后检索返回结果() {
        // 1. 准备：原始查询包含填充词
        String rawQuery = "嗯那个帮我查一下之前聊过的旅行计划";

        // 2. Mock LLM 返回改写变体
        var llmResponse = new LlmResponse(
                "[\"之前讨论的旅行计划\", \"旅行安排和目的地\"]",
                10, 20, "provider-1", "model-1", 100, false);
        when(llmRouter.call(any(LlmRequest.class))).thenReturn(llmResponse);

        // 3. Mock 向量检索返回结果
        var vectorResult = new VectorSearchResult("entity-travel-1", 0.85f);
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat()))
                .thenReturn(List.of(vectorResult));

        // Mock 实体详情
        var now = Instant.now();
        var entity = new TemporalEntity(
                "entity-travel-1",
                com.lifepilot.memory.semantic.EntityType.EVENT,
                "旅行计划",
                "去年讨论的日本旅行计划",
                Map.of(), 1, true, now, null, null,
                0.9f, 0.8f, 1, now, now, now);
        when(semanticMemory.findByIds(any())).thenReturn(Map.of("entity-travel-1", entity));

        // Mock FTS 和 Graph 返回空
        when(ftsSearcher.search(anyString(), anyInt())).thenReturn(List.of());
        when(graphTraverser.traverse(anyString(), anyInt())).thenReturn(List.of());

        // 4. 执行端到端流程
        // Step 1: QueryRefiner 精炼
        String refined = queryRefiner.refine(rawQuery);
        assertFalse(refined.contains("嗯"), "精炼后应移除填充词'嗯'");
        assertFalse(refined.contains("那个"), "精炼后应移除填充词'那个'");

        // Step 2: QueryRewriter 改写
        var rewriteResult = queryRewriter.rewrite(refined);
        assertEquals(refined, rewriteResult.primaryQuery());
        assertFalse(rewriteResult.rewrittenQueries().isEmpty(), "rewrite 模式应返回改写变体");

        // Step 3: HybridRetriever 检索（原始查询 + 改写变体）
        var weights = RetrievalWeights.DEFAULT;
        var primaryResults = hybridRetriever.retrieve(rewriteResult.primaryQuery(), 10, weights);

        // 5. 验证：管线产出了检索结果
        assertFalse(primaryResults.isEmpty(), "检索应返回结果");
        assertEquals("entity-travel-1", primaryResults.getFirst().entityId());
    }

    @Test
    void 端到端_none模式下QueryRewriter直通不调用LLM() {
        // 配置 none 模式
        properties.getRetrieval().setQueryRewriteMode("none");
        queryRewriter = new QueryRewriter(llmRouter, properties, promptRegistry);

        String rawQuery = "帮我查一下之前的会议记录";
        String refined = queryRefiner.refine(rawQuery);

        var rewriteResult = queryRewriter.rewrite(refined);

        // none 模式：直通返回，不调用 LLM
        assertEquals(refined, rewriteResult.primaryQuery());
        assertTrue(rewriteResult.rewrittenQueries().isEmpty());
        assertTrue(rewriteResult.hydeEmbedding().isEmpty());
        verify(llmRouter, never()).call(any(LlmRequest.class));
    }

    @Test
    void 端到端_LLM失败时降级为原始查询检索() {
        // Mock LLM 调用失败
        when(llmRouter.call(any(LlmRequest.class)))
                .thenThrow(new RuntimeException("LLM 服务不可用"));

        // Mock 向量检索返回结果
        var vectorResult = new VectorSearchResult("entity-meeting-1", 0.75f);
        when(vectorSearcher.searchEntities(anyString(), anyInt(), anyFloat()))
                .thenReturn(List.of(vectorResult));
        var now = Instant.now();
        var entity = new TemporalEntity(
                "entity-meeting-1",
                com.lifepilot.memory.semantic.EntityType.EVENT,
                "会议记录",
                "上周的项目会议",
                Map.of(), 1, true, now, null, null,
                0.9f, 0.7f, 1, now, now, now);
        when(semanticMemory.findByIds(any())).thenReturn(Map.of("entity-meeting-1", entity));
        when(ftsSearcher.search(anyString(), anyInt())).thenReturn(List.of());
        when(graphTraverser.traverse(anyString(), anyInt())).thenReturn(List.of());

        String rawQuery = "帮我查一下之前的会议记录";
        String refined = queryRefiner.refine(rawQuery);

        // QueryRewriter 降级返回原始查询
        var rewriteResult = queryRewriter.rewrite(refined);
        assertEquals(refined, rewriteResult.primaryQuery());
        assertTrue(rewriteResult.rewrittenQueries().isEmpty());

        // 使用降级后的原始查询检索仍能返回结果
        var results = hybridRetriever.retrieve(rewriteResult.primaryQuery(), 10, RetrievalWeights.DEFAULT);
        assertFalse(results.isEmpty(), "降级后仍应能检索到结果");
    }
}
