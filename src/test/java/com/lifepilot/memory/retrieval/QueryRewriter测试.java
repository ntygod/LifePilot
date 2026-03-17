package com.lifepilot.memory.retrieval;

import com.lifepilot.llm.LlmRequest;
import com.lifepilot.llm.LlmResponse;
import com.lifepilot.llm.LlmRouter;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.memory.config.MemoryProperties;
import com.lifepilot.prompt.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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

    private LlmRouter llmRouter;
    private PromptRegistry promptRegistry;
    private MemoryProperties properties;

    @BeforeEach
    void setUp() {
        llmRouter = mock(LlmRouter.class);
        promptRegistry = mock(PromptRegistry.class);
        properties = new MemoryProperties();
        when(promptRegistry.render(anyString(), anyMap())).thenReturn("mock prompt");
    }

    @Test
    void rewrite模式返回改写变体列表() {
        properties.getRetrieval().setQueryRewriteMode("rewrite");
        properties.getRetrieval().setMaxRewrites(3);

        var llmResponse = new LlmResponse(
                "[\"改写查询1\", \"改写查询2\", \"改写查询3\"]",
                10, 20, "provider-1", "model-1", 100, false);
        when(llmRouter.call(any(LlmRequest.class))).thenReturn(llmResponse);

        var rewriter = new QueryRewriter(llmRouter, properties, promptRegistry);
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
        properties.getRetrieval().setQueryRewriteMode("hyde");

        var llmResponse = new LlmResponse(
                "这是一段假设性文档内容，描述了用户查询的理想回答。",
                10, 30, "provider-1", "model-1", 150, false);
        when(llmRouter.call(any(LlmRequest.class))).thenReturn(llmResponse);

        float[] mockEmbedding = new float[]{0.1f, 0.2f, 0.3f};
        when(llmRouter.embed(anyString())).thenReturn(mockEmbedding);

        var rewriter = new QueryRewriter(llmRouter, properties, promptRegistry);
        var result = rewriter.rewrite("原始查询");

        assertEquals("原始查询", result.primaryQuery());
        assertTrue(result.rewrittenQueries().isEmpty());
        assertTrue(result.hydeEmbedding().isPresent());
        assertArrayEquals(mockEmbedding, result.hydeEmbedding().get());
    }

    @Test
    void none模式直通返回原始查询() {
        properties.getRetrieval().setQueryRewriteMode("none");

        var rewriter = new QueryRewriter(llmRouter, properties, promptRegistry);
        var result = rewriter.rewrite("原始查询");

        assertEquals("原始查询", result.primaryQuery());
        assertTrue(result.rewrittenQueries().isEmpty());
        assertTrue(result.hydeEmbedding().isEmpty());
        // none 模式不应调用 LLM
        verify(llmRouter, never()).call(any(LlmRequest.class));
    }

    @Test
    void LLM超时降级() {
        properties.getRetrieval().setQueryRewriteMode("rewrite");
        properties.getRetrieval().setRewriteTimeoutMs(1); // 极短超时

        // 模拟 LLM 调用耗时超过超时时间
        when(llmRouter.call(any(LlmRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(100); // 模拟耗时
            return new LlmResponse("[\"改写\"]", 10, 20, "p", "m", 100, false);
        });

        var rewriter = new QueryRewriter(llmRouter, properties, promptRegistry);
        var result = rewriter.rewrite("原始查询");

        // 超时应降级
        assertEquals("原始查询", result.primaryQuery());
        assertTrue(result.rewrittenQueries().isEmpty());
        assertTrue(result.hydeEmbedding().isEmpty());
    }

    @Test
    void LLM不可用降级() {
        properties.getRetrieval().setQueryRewriteMode("rewrite");

        when(llmRouter.call(any(LlmRequest.class)))
                .thenThrow(new LlmUnavailableException("LLM 不可用", "test", List.of()));

        var rewriter = new QueryRewriter(llmRouter, properties, promptRegistry);
        var result = rewriter.rewrite("原始查询");

        assertEquals("原始查询", result.primaryQuery());
        assertTrue(result.rewrittenQueries().isEmpty());
        assertTrue(result.hydeEmbedding().isEmpty());
    }
}
