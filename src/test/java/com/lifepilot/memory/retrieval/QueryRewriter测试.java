package com.lifepilot.memory.retrieval;

import com.lifepilot.embedding.router.EmbeddingRouter;
import com.lifepilot.embedding.router.EmbeddingUseCase;
import com.lifepilot.generation.router.GenerationRouter;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.memory.retrieval.config.MemoryRetrievalProperties;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * QueryRewriter 单元测试。
 *
 * @author zsg
 * @since 2026-03-17
 */
class QueryRewriter测试 {

    private GenerationRouter generationRouter;
    private EmbeddingRouter embeddingRouter;
    private PromptRegistry promptRegistry;
    private MemoryRetrievalProperties properties;

    @BeforeEach
    void setUp() {
        generationRouter = mock(GenerationRouter.class);
        embeddingRouter = mock(EmbeddingRouter.class);
        promptRegistry = mock(PromptRegistry.class);
        properties = new MemoryRetrievalProperties();
        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");
    }

    @Test
    void rewrite模式返回改写变体列表() {
        properties.setQueryRewriteMode("rewrite");
        properties.setMaxRewrites(3);

        var llmResponse = new LlmResponse("[\"改写查询1\", \"改写查询2\", \"改写查询3\"]", null, null, List.of(), Map.of(), 10, 20, null, 0, "provider-1", "model-1", 100, false);
        when(generationRouter.call(anyString(), anyString(), isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), any())).thenReturn(llmResponse);

        var rewriter = new QueryRewriter(generationRouter, embeddingRouter, properties, promptRegistry);
        var result = rewriter.rewrite("原始查询");

        assertEquals("原始查询", result.primaryQuery());
        assertEquals(3, result.rewrittenQueries().size());
        assertEquals("改写查询1", result.rewrittenQueries().get(0));
        assertEquals("改写查询2", result.rewrittenQueries().get(1));
        assertEquals("改写查询3", result.rewrittenQueries().get(2));
        assertTrue(result.hydeEmbedding().isEmpty());
    }

    @Test
    void hyde模式返回hydeEmbedding() {
        properties.setQueryRewriteMode("hyde");

        var llmResponse = new LlmResponse("这是一段假设性文档内容，描述了用户查询的理想回答。", null, null, List.of(), Map.of(), 10, 30, null, 0, "provider-1", "model-1", 150, false);
        when(generationRouter.call(anyString(), anyString(), isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), any())).thenReturn(llmResponse);

        float[] mockEmbedding = new float[]{0.1f, 0.2f, 0.3f};
        when(embeddingRouter.embed(anyString(), eq(EmbeddingUseCase.MEMORY), isNull(), isNull()))
                .thenReturn(mockEmbedding);

        var rewriter = new QueryRewriter(generationRouter, embeddingRouter, properties, promptRegistry);
        var result = rewriter.rewrite("原始查询");

        assertEquals("原始查询", result.primaryQuery());
        assertTrue(result.rewrittenQueries().isEmpty());
        assertTrue(result.hydeEmbedding().isPresent());
        assertArrayEquals(mockEmbedding, result.hydeEmbedding().get());
    }

    @Test
    void none模式直通返回原始查询() {
        properties.setQueryRewriteMode("none");

        var rewriter = new QueryRewriter(generationRouter, embeddingRouter, properties, promptRegistry);
        var result = rewriter.rewrite("原始查询");

        assertEquals("原始查询", result.primaryQuery());
        assertTrue(result.rewrittenQueries().isEmpty());
        assertTrue(result.hydeEmbedding().isEmpty());
        verifyNoInteractions(generationRouter, embeddingRouter);
    }

    @Test
    void 生成调用失败时应抛出() {
        properties.setQueryRewriteMode("rewrite");
        when(generationRouter.call(anyString(), anyString(), isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), any()))
                .thenThrow(new RuntimeException("生成失败"));

        var rewriter = new QueryRewriter(generationRouter, embeddingRouter, properties, promptRegistry);

        assertThrows(RuntimeException.class, () -> rewriter.rewrite("原始查询"));
    }

    @Test
    void rewrite模式返回非JSON数组时按契约失败() {
        properties.setQueryRewriteMode("rewrite");
        var llmResponse = new LlmResponse("不是 JSON 数组", null, null, List.of(), Map.of(),
                10, 20, null, 0, "provider-1", "model-1", 100, false);
        when(generationRouter.call(anyString(), anyString(), isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), any())).thenReturn(llmResponse);

        var rewriter = new QueryRewriter(generationRouter, embeddingRouter, properties, promptRegistry);

        assertThrows(IllegalStateException.class, () -> rewriter.rewrite("原始查询"));
    }

    @Test
    void rewrite模式返回非字符串数组项时按契约失败() {
        properties.setQueryRewriteMode("rewrite");
        var llmResponse = new LlmResponse("[\"有效改写\", 42]", null, null, List.of(), Map.of(),
                10, 20, null, 0, "provider-1", "model-1", 100, false);
        when(generationRouter.call(anyString(), anyString(), isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), any())).thenReturn(llmResponse);

        var rewriter = new QueryRewriter(generationRouter, embeddingRouter, properties, promptRegistry);

        assertThrows(IllegalStateException.class, () -> rewriter.rewrite("原始查询"));
    }

    @Test
    void rewrite模式返回首尾空白数组项时按契约失败() {
        properties.setQueryRewriteMode("rewrite");
        var llmResponse = new LlmResponse("[\" 有效改写\"]", null, null, List.of(), Map.of(),
                10, 20, null, 0, "provider-1", "model-1", 100, false);
        when(generationRouter.call(anyString(), anyString(), isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), any())).thenReturn(llmResponse);

        var rewriter = new QueryRewriter(generationRouter, embeddingRouter, properties, promptRegistry);

        assertThrows(IllegalStateException.class, () -> rewriter.rewrite("原始查询"));
    }

    @Test
    void rewrite模式返回空白数组项时按契约失败() {
        properties.setQueryRewriteMode("rewrite");
        var llmResponse = new LlmResponse("[\"有效改写\", \"\"]", null, null, List.of(), Map.of(),
                10, 20, null, 0, "provider-1", "model-1", 100, false);
        when(generationRouter.call(anyString(), anyString(), isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), any())).thenReturn(llmResponse);

        var rewriter = new QueryRewriter(generationRouter, embeddingRouter, properties, promptRegistry);

        assertThrows(IllegalStateException.class, () -> rewriter.rewrite("原始查询"));
    }

    @Test
    void rewrite模式返回改写数量超过上限时按契约失败() {
        properties.setQueryRewriteMode("rewrite");
        properties.setMaxRewrites(2);
        var llmResponse = new LlmResponse("[\"改写查询1\", \"改写查询2\", \"改写查询3\"]", null, null,
                List.of(), Map.of(), 10, 20, null, 0, "provider-1", "model-1", 100, false);
        when(generationRouter.call(anyString(), anyString(), isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), any())).thenReturn(llmResponse);

        var rewriter = new QueryRewriter(generationRouter, embeddingRouter, properties, promptRegistry);

        assertThrows(IllegalStateException.class, () -> rewriter.rewrite("原始查询"));
    }

    @Test
    void 生成服务不可用时应抛出() {
        properties.setQueryRewriteMode("rewrite");
        when(generationRouter.call(anyString(), anyString(), isNull(), isNull(), isNull(),
                eq(GenerationCapability.CHAT), any()))
                .thenThrow(new LlmUnavailableException("生成服务不可用", "memory_query_rewrite", List.of()));

        var rewriter = new QueryRewriter(generationRouter, embeddingRouter, properties, promptRegistry);

        assertThrows(LlmUnavailableException.class, () -> rewriter.rewrite("原始查询"));
    }
}
